package com.quanta.demo0.search.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.search.properties.SearchTrendingProperties;
import com.quanta.demo0.search.service.TrendingCacheService;
import com.quanta.demo0.search.vo.SearchTrendingVO;
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
 * 热榜缓存实现类。
 * 流程：
 * 1. 从本地缓存获取热榜数据，若不存在则从L2缓存或数据源加载。
 * 2. 失效热榜缓存时，先从本地缓存失效，再从Redis中失效。
 *
 * ============================================================
 * 【为什么 L1 只存 1 条、10 秒，L2 却能放 300±60 秒？】
 * ============================================================
 * L1（Caffeine）按配置构建：maximumSize=1、expireAfterWrite=10s
 * （见构造器，对应 application.yml 的 l1-maximum-size / l1-ttl-seconds）。
 * 全局只有一个 key（"trending"），L1 的唯一职责是替 Redis 挡住
 * 同一实例内的并发读；而 10 秒超短 TTL 意味着：跨实例失效时
 * **只需清掉发起失效那个实例的 L1，其他实例最迟 10 秒后读 L2 自愈，
 * 不需要任何广播/消息机制**——用一点可容忍的旧数据换简单可靠。
 *
 * L2（Redis，key = search:trending:all，值是 JSON 序列化的 SearchTrendingVO）
 * TTL 基础 300s、±60s 随机抖动：基础 TTL 够长才能扛住读流量；
 * 抖动是为了防"缓存雪崩"——多实例同时回填的缓存若同时到期，
 * 会同一瞬间集体打到 MySQL（见 {@link #jitteredRedisTtlSeconds()}）。
 *
 * ============================================================
 * 【为什么失效用"写墓碑"而不是 DELETE？】
 * ============================================================
 * evict() 往 L2 写一个 "__INVALIDATED__:<uuid>" 墓碑值（TTL 360s），
 * 并配合 lua/trending-cache-compare-set.lua 做**条件回填**（CAS）：
 * 本类与 content 域 ContentDetailCacheServiceImpl 共用同一份 Lua 脚本。
 * 三类并发窗口的旧写入全部被拦截：
 * - 回源前读到"旧值"的 loader：CAS expected=旧值，而当前值已是墓碑 → 拒绝；
 * - 回源前读到"key 不存在"的 loader：CAS mode=ABSENT，而墓碑让 key 变成存在 → 拒绝；
 *   （这一条是 DELETE 做不到的：DELETE 后 key 仍是不存在，ABSENT 回填照样成功）
 * - 失效之后才启动的新 loader：读到墓碑当作 miss，重新加载后以墓碑为 expected
 *   覆盖写入 → 正常更新。
 * 墓碑 TTL 360s = 基础 300s + 抖动上限 60s，不短于任何已写入值的最大存活期；
 * 过期后 key 自然回到"不存在"状态，失效状态不会永久霸占缓存位。
 */
@Service
@Slf4j
public class TrendingCacheServiceImpl implements TrendingCacheService {

    /** L1（Caffeine）固定单 key：热榜全站就一份聚合结果，无需按维度分 key。 */
    private static final String LOCAL_CACHE_KEY = "trending";
    /**
     * 【墓碑】失效标记前缀：写到 L2 key 上表示"数据已失效，旧值不可再回填"。
     * 后缀拼随机 UUID，保证每次失效的墓碑值唯一——读到旧墓碑的回填请求
     * 无法与新墓碑 MATCH，天然多一层防护。
     */
    private static final String TOMBSTONE_PREFIX = "__INVALIDATED__:";
    /**
     * 墓碑存活 360s = L2 基础 TTL 300s + 抖动上限 60s：
     * 不短于任何已写入缓存值的最大存活期，失效窗口覆盖所有正常回填；
     * 过期后 key 回归"不存在"，不会永久挡住新数据。
     */
    private static final long TOMBSTONE_TTL_SECONDS = 360L;
    /** L2 条件回填脚本（lua/trending-cache-compare-set.lua），静态初始化只解析一次。 */
    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT;

    static {
        COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>();
        COMPARE_AND_SET_SCRIPT.setLocation(
                new ClassPathResource("lua/trending-cache-compare-set.lua")
        );
        COMPARE_AND_SET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final SearchTrendingProperties properties;
    private final Cache<String, SearchTrendingVO> localCache;

    /**
     * L1 缓存在构造器里按配置一次性构建：maximumSize=1、expireAfterWrite=10s。
     * 【坑】L1 参数只在启动时读取一次，运行期改配置不会影响已构建的 Caffeine 实例。
     */
    public TrendingCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            SearchTrendingProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getL1MaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getL1TtlSeconds()))
                .build();
    }

    // 从本地缓存获取热榜数据，若不存在则从L2缓存或数据源加载
    /**
     * 读路径入口：L1 命中直接返回；未命中才走 {@link #loadFromL2OrSource}。
     * 【坑】Caffeine 的 get(key, mappingFunction) 保证同一 key 的并发加载
     * 只执行一次，因此回源 loader（内含 Redis + MySQL 查询）不会在本实例内被打穿，
     * 天然起到了"进程内互斥回源"的作用。
     */
    @Override
    public SearchTrendingVO getOrLoad(Supplier<SearchTrendingVO> loader) {
        return localCache.get(LOCAL_CACHE_KEY, ignored -> loadFromL2OrSource(loader));
    }

    // 失效热榜缓存
    /**
     * 失效两步走：先清本实例 L1（立即生效），再往 L2 写墓碑（拦截其他窗口的旧回填）。
     * 【取舍】L2 写墓碑失败只 warn 不抛：本实例 L1 已清、其他实例靠 10s 的 L1 TTL
     * 自愈、Redis 里的旧值最多再存活一轮自身 TTL（最长 360s）后自然消失——
     * 失效是"尽力而为 + TTL 兜底"，绝不因缓存问题阻塞调用方的事务提交。
     */
    @Override
    public void evict() {
        localCache.invalidate(LOCAL_CACHE_KEY);
        // UUID 保证每次失效的墓碑值唯一：读到旧墓碑的回填无法与新墓碑 MATCH。
        String tombstone = TOMBSTONE_PREFIX + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    tombstone,
                    TOMBSTONE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
            log.info("热榜缓存已失效，key={}", RedisConstants.SEARCH_TRENDING_ALL_KEY);
        } catch (Exception exception) {
            log.warn(
                    "热榜 L2 失效失败，等待 TTL 自愈，key={}, stage=tombstone-write",
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    exception
            );
        }
    }
    // 从Redis缓存或数据源加载热榜数据
    /**
     * L1 未命中后的读路径：L2 里有合法 JSON 就直接返回；
     * 墓碑、空值、损坏 JSON 一律视为 miss，回源 loader 后做条件回填。
     * 【坑】L2 读取本身抛异常时直接回源且【禁止回填】（见下 return loader.get()）：
     * 此时 observedRaw 未知，无论 ABSENT 还是 MATCH 模式都是盲写，
     * 宁可多查一次 MySQL 也不能冒覆盖别人新数据的风险。
     */
    private SearchTrendingVO loadFromL2OrSource(Supplier<SearchTrendingVO> loader) {
        String observedRaw;
        try {
            observedRaw = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.SEARCH_TRENDING_ALL_KEY);
        } catch (Exception exception) {
            log.warn(
                    "热榜 L2 读取失败，本次禁止回填 L2，key={}, stage=read",
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    exception
            );
            return loader.get();
        }

        // 只有"非空且非墓碑"才算 L2 命中；墓碑命中说明数据刚失效，必须当 miss 处理。
        if (observedRaw != null && !observedRaw.startsWith(TOMBSTONE_PREFIX)) {
            try {
                SearchTrendingVO cached = JSON.parseObject(
                        observedRaw,
                        SearchTrendingVO.class
                );
                if (cached != null) {
                    return cached;
                }
                log.warn(
                        "热榜 Redis JSON 为空，回退事实源，key={}, stage=deserialize",
                        RedisConstants.SEARCH_TRENDING_ALL_KEY
                );
            } catch (Exception exception) {
                log.warn(
                        "热榜 Redis JSON 损坏，回退事实源，key={}, stage=deserialize",
                        RedisConstants.SEARCH_TRENDING_ALL_KEY,
                        exception
                );
            }
        }

        SearchTrendingVO loaded = loader.get();
        // 以"读到的旧状态"为 CAS 依据做条件回填：期间若发生过 evict 或他人更新，写入会被拒绝。
        conditionalWrite(observedRaw, loaded);
        return loaded;
    }
    // 条件回填热榜数据
    /**
     * 条件回填（CAS 语义）：把回源前"观察到的 L2 状态"传给 Lua 脚本做原子判断——
     * observedRaw 为 null 用 ABSENT 模式（key 仍不存在才写）；
     * 否则用 MATCH 模式（当前值仍等于观察值才写），写入时带随机抖动 TTL。
     * 【为什么必须 CAS？】loader 回源耗时期间可能发生 evict（写入了墓碑）
     * 或其他实例已写入更新的值；无脑 SET 会把旧数据盖回 Redis（缓存旧写）。
     * 【被拒绝是正常现象】返回非 1 说明有更新鲜的写入赢了竞争，只打 debug 即可。
     */
    private void conditionalWrite(String observedRaw, SearchTrendingVO value) {
        // ABSENT：回源前 key 不存在 → 仅当现在仍不存在才允许写入。
        String mode = observedRaw == null ? "ABSENT" : "MATCH";
        // MATCH 模式的 expected：必须与回源前逐字节一致，任何变化（含墓碑）都会让 CAS 失败。
        String expected = observedRaw == null ? "" : observedRaw;
        String json = JSON.toJSONString(value);
        long ttlSeconds = jitteredRedisTtlSeconds();
        try {
            Long written = stringRedisTemplate.execute(
                    COMPARE_AND_SET_SCRIPT,
                    List.of(RedisConstants.SEARCH_TRENDING_ALL_KEY),
                    mode,
                    expected,
                    json,
                    String.valueOf(ttlSeconds)
            );
            if (!Long.valueOf(1L).equals(written)) {
                log.debug(
                        "热榜 L2 条件回填被拒绝，期间缓存状态已变化，key={}",
                        RedisConstants.SEARCH_TRENDING_ALL_KEY
                );
            }
        } catch (Exception exception) {
            log.warn(
                    "热榜 L2 条件回填失败，key={}, stage=compare-and-set",
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    exception
            );
        }
    }
    // 生成随机TTL, 避免热榜缓存雪崩,意思是当所有请求同时访问热榜时, 会随机分布到不同的时间点，避免失效同时打到数据库。
    private long jitteredRedisTtlSeconds() {
        long base = properties.getRedisTtlSeconds();
        long jitter = properties.getRedisTtlJitterSeconds();
        if (jitter == 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }
}
