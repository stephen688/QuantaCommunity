package com.quanta.demo0.content.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 帖子详情两级缓存。Redis 只保存稳定快照和可短暂缓存的负结果，访问者状态不进入缓存。
 *
 * ============================================================
 * 【总览：这一张图把整个类串起来】
 * ============================================================
 *   读请求 → L1 (Caffeine, 本 JVM, 秒级 TTL)
 *            miss ↓
 *          L2 (Redis, 分钟级 TTL + 抖动)
 *            miss / 命中墓碑 ↓
 *          loader 查 MySQL（事实源）→ Lua 条件回填 L2 → 回填 L1
 *
 *   写请求（发布/删帖/审核）→ Invalidator: 清 L1 + 在 L2 写"墓碑"
 *
 * 三个经典缓存问题在这里各有真实落点（不是教科书假设）：
 *   穿透 = 已删/未审的帖子被反复请求 → **负缓存**（NOT_FOUND 也缓存，短 TTL）
 *   击穿 = 热帖缓存到期瞬间大量请求同时打到 DB → **逻辑过期式失效 + 墓碑互斥**（见下）
 *   雪崩 = 同批 key 同时到期 → **TTL 随机抖动**（见 ttlSeconds）
 *
 * ============================================================
 * 【墓碑（Tombstone）：本类最精巧的设计】
 * ============================================================
 * 问题：多实例部署时，实例 A 改了帖子并 evict，实例 B 的 loader 正好在旧数据上
 * 慢查询 —— A 的失效先执行、B 的回填后到达，B 把**改之前的旧值**写回了 L2，
 * 从此所有实例读到的都是旧快照，直到 TTL 过期（分钟级脏窗口，且不可自愈）。
 *
 * 解法：evict 不是简单 DELETE，而是写一个随机 UUID 的墓碑值。
 * loader 回填时不直接 SET，而是走 Lua 脚本**条件回填**（COMPARE_AND_SET_SCRIPT）：
 *   - 模式 ABSENT（key 不存在）→ 直接写
 *   - 模式 MATCH（当前值 == loader 读到的 observedRaw）→ 说明期间没人动过，写
 *   - 否则（比如当前值是墓碑，或已被别的实例刷新）→ 拒绝回填
 * 效果：旧 loader 的回填会撞上墓碑被拒绝 —— **用一次 CAS 把"后到的旧值"挡在门外**。
 * 墓碑自身有 TTL（分钟级），过期后 key 自然回到"不存在"状态，允许新回填。
 *
 * 【面试追问：为什么不直接用 Redis 的 watch/multi 或分布式锁？】
 * WATCH 是乐观锁，单实例够用但每次回填多一轮 watch/exec 往返；分布式锁是重武器。
 * Lua 脚本在 Redis 单线程里原子执行，一次往返解决，且模式就两种，复杂度可控。
 * —— 这也是"为什么用 Lua"的标准答案：**多命令的原子性需求 + 单次往返**。
 *
 * 【面试追问：为什么 L1 失效只能清本实例，怎么容忍的？】
 * Caffeine 是 JVM 内的，evict 够不到别的实例。容忍手段是 L1 TTL 很短（秒级），
 * 其他实例最多脏这几秒。推论：**L1 只放"陈旧几秒无感"的数据（快照），
 * 不放余额/权限这类强一致数据** —— 这就是本地缓存的准入标准。
 */
@Service
@Slf4j
public class ContentDetailCacheServiceImpl implements ContentDetailCacheService {

    private static final String TOMBSTONE_PREFIX = "__INVALIDATED__:";
    // Lua 脚本静态持有、全实例只解析一次：execute 时 Redis 按 SHA1 缓存脚本文本（EVALSHA），
    // 反复回填也只传参数不传脚本。脚本体在 resources/lua 下，和业务代码分开 review。
    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT;

    static {
        COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>();
        COMPARE_AND_SET_SCRIPT.setLocation(
                new ClassPathResource("lua/trending-cache-compare-set.lua")
        );
        COMPARE_AND_SET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ReadPathCacheProperties properties;
    private final Cache<Long, ContentDetailCacheEntry> localCache;

    public ContentDetailCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            ReadPathCacheProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        // L1 的两个参数都来自配置（quanta.cache.read-path.content-detail）：
        // maximumSize —— 本地缓存必须限容量：Caffeine 在 JVM 堆里，不做上限的话
        //   大量内容详情能把老年代撑爆（Redis 有自己的内存淘汰策略，本地缓存只能自己管）；
        // expireAfterWrite=30s —— L1 TTL 故意远短于 L2 的 300s：Invalidator 够不到
        //   其他实例的本地缓存，那些实例的脏数据全靠这 30s 兜底（推导见类注释末段）。
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getContentDetail().getL1MaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getContentDetail().getL1TtlSeconds()))
                .build();
    }

    /**
     * 读入口：L1 优先。
     * 注意 Caffeine.get(key, mappingFunction) 的语义：mappingFunction 返回 null 不缓存 ——
     * 所以"DB 里没有"不会把 L1 打穿，每个 miss 请求都会走一遍 loadFromL2OrSource。
     * 这不是 bug：L2 的负缓存已经挡住了 DB，L1 不缓 null 只是多一次 Redis GET。
     */
    @Override
    public ContentDetailCacheEntry getOrLoad(
            Long contentId,
            Supplier<ContentDetailCacheEntry> loader
    ) {
        return localCache.get(contentId, ignored -> loadFromL2OrSource(contentId, loader));
    }

    /**
     * 失效入口（写路径调用）。
     * 两步：清本实例 L1 + 在 L2 写墓碑（不是 DELETE！）。
     * 墓碑的作用见类注释：拦截"还在飞行的旧 loader"的回填。
     *
     * 【失败语义】L2 写墓碑失败只 warn 不抛 —— 因为墓碑丢了还有 L2 自身 TTL 兜底，
     * 最坏退化成"分钟级脏读"，不该因此让写请求 500。
     * **缓存组件的一切异常都不该传染给业务主流程**，这是缓存代码的第一军规。
     */
    @Override
    public void evict(Long contentId) {
        localCache.invalidate(contentId);
        String key = key(contentId);
        // 墓碑值每次随机：保证它必然不等于任何快照 JSON —— 任何还拿着旧值当 expected
        // 的条件回填（MATCH 模式）都会在这里撞车被拒，这就是拦截"飞行中的旧 loader"的机关
        String tombstone = TOMBSTONE_PREFIX + UUID.randomUUID();// 墓碑值每次随机：保证它必然不等于任何快照 JSON —— 任何还拿着旧值当 expected
        // 的条件回填（MATCH 模式）都会在这里撞车被拒，这就是拦截"飞行中的旧 loader"的机关
        try {
            stringRedisTemplate.opsForValue().set(
                    key,
                    tombstone,
                    properties.getContentDetail().getTombstoneTtlSeconds(),
                    TimeUnit.SECONDS
            );
            log.info("详情缓存已失效，key={}", key);
        } catch (Exception exception) {
            log.warn(
                    "详情 L2 失效失败，等待 TTL 自愈，key={}, stage=tombstone-write",
                    key,
                    exception
            );
        }
    }

    /**
     * L2 读取 + miss 时回源。整个方法体贯彻一条军规：**Redis 挂了不能拖垮读接口**。
     *
     * 三个分支：
     *   a. Redis GET 异常 → 直接 return loader.get()，**跳过 conditionalWrite** ——
     *      L2 故障期间禁止回填：故障期写入的数据可能残缺（网络半途），
     *      恢复后没人知道它是坏值，宁可这轮不缓存（TTL/下次读会自然补上）。
     *   b. 命中正常 JSON → 反序列化返回（JSON 损坏 → 当 miss 处理，回源兜底）
     *   c. miss / 命中墓碑（startsWith(TOMBSTONE_PREFIX) 当作 miss）→ loader 回源，
     *      然后 conditionalWrite 条件回填。
     *
     * 注意 observedRaw 的用途：它不只是判断 miss —— 它被原样传给 conditionalWrite
     * 当作 CAS 的 expected 值。**"我读到的是 X，我写回时也要求它还是 X"**，
     * 这就是 optimistic read 的完整闭环。
     */
    private ContentDetailCacheEntry loadFromL2OrSource(
            Long contentId,
            Supplier<ContentDetailCacheEntry> loader
    ) {
        String key = key(contentId);
        String observedRaw;
        try {
            observedRaw = stringRedisTemplate.opsForValue().get(key);// 读取 L2 缓存
        } catch (Exception exception) {
            log.warn(
                    "详情 L2 读取失败，本次禁止回填 L2，key={}, stage=read",
                    key,
                    exception
            );
            return loader.get();
        }
// 命中正常 JSON ，反序列化返回
        if (observedRaw != null && !observedRaw.startsWith(TOMBSTONE_PREFIX)) {
            try {
                ContentDetailCacheEntry cached = objectMapper.readValue(
                        observedRaw,
                        ContentDetailCacheEntry.class
                );
                if (cached != null && cached.state() != null) {
                    return cached;
                }
                log.warn("详情 L2 JSON 为空，回退事实源，key={}, stage=deserialize", key);
            } catch (Exception exception) {
                log.warn(
                        "详情 L2 JSON 损坏，回退事实源，key={}, stage=deserialize",
                        key,
                        exception
                );
            }
        }

        ContentDetailCacheEntry loaded = loader.get();
        conditionalWrite(key, observedRaw, loaded);
        return loaded;
    }

    /**
     * 条件回填：Lua 脚本原子执行"比较-再写"。
     *
     * 两种模式：
     *   ABSENT —— loader 回源时 key 不存在，回填要求它现在仍然不存在
     *             （防止覆盖 evict 刚写的墓碑或别实例刚写的新值）；
     *   MATCH  —— 回填要求当前值仍等于 loader 读到的 observedRaw
     *             （防止覆盖期间被别的实例刷新的新值）。
     *
     * 被拒绝（返回 != 1）不是错误，只是说明"有并发竞争，我的值已过期"——
     * debug 日志即可，下次读自然重走。
     * 序列化失败同样只跳过回填不抛异常 —— 读接口的缓存回填永远 fail-open。
     */
    private void conditionalWrite(
            String key,
            String observedRaw,
            ContentDetailCacheEntry value
    ) {
        String mode = observedRaw == null ? "ABSENT" : "MATCH";
        String expected = observedRaw == null ? "" : observedRaw;
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            log.warn(
                    "详情缓存序列化失败，跳过 L2 回填，key={}, stage=serialize",
                    key,
                    exception
            );
            return;
        }

        long ttlSeconds = ttlSeconds(value);
        try {
            Long written = stringRedisTemplate.execute(
                    COMPARE_AND_SET_SCRIPT,
                    List.of(key),
                    mode,
                    expected,
                    json,
                    String.valueOf(ttlSeconds)
            );
            if (!Long.valueOf(1L).equals(written)) {
                log.debug("详情 L2 条件回填被拒绝，期间缓存状态已变化，key={}", key);
            }
        } catch (Exception exception) {
            log.warn(
                    "详情 L2 条件回填失败，key={}, stage=compare-and-set",
                    key,
                    exception
            );
        }
    }

    /**
     * TTL 策略：正常值 = 基础 TTL ± 抖动（防雪崩），负结果 = 短 TTL（防穿透）。
     *
     * 【面试高频：TTL 抖动为什么 ± 而不是 +？】
     * 只加不减会让所有 key 的有效期被系统性拉长（陈旧窗口变大）；
     * ± 抖动在"打散到期时间"的同时不改变平均 TTL。
     *
     * 【负缓存 TTL 为什么短？】
     * 负结果（NOT_FOUND/DELETED/未审核）挡的是穿透，但"不存在"是会变的
     * （审核通过、帖子恢复）—— TTL 越长，"审核通过了用户还看不到"的风险越大。
     * 短 TTL（秒级~60s）配合写路径的 evict 主动清墓碑，两边夹住脏窗口。
     */
    private long ttlSeconds(ContentDetailCacheEntry value) {
        if (value == null || value.state() != ContentDetailState.FOUND) {
            return properties.getContentDetail().getNegativeTtlSeconds();
        }
        long base = properties.getContentDetail().getRedisTtlSeconds();
        long jitter = properties.getContentDetail().getRedisTtlJitterSeconds();
        if (jitter == 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }

    /** Redis key：前缀统一收口在 RedisConstants（content:detail:{contentId}），避免各处手拼 key 漂移。 */
    private String key(Long contentId) {
        return RedisConstants.CONTENT_DETAIL_KEY + contentId;
    }
}
