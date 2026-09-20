# 热榜聚合双层缓存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `GET /search/trending` 建立 Caffeine L1、Redis L2、MySQL/排行源回退链路，并阻止事务失效期间的旧快照重新污染 L2。

**Architecture:** `SearchServiceImpl` 只编排缓存和数据构建器；L2 使用单 Key，值为正常 JSON 或唯一失效墓碑。缓存未命中时记录所观察到的 Redis 原值，数据构建完成后通过 Lua compare-and-set 条件回填；事务提交后的失效以原子 `SET tombstone EX 360` 取代普通 `DEL`，从而关闭“读旧库后覆盖失效”的竞态窗口。

**Tech Stack:** Java 17、Spring Boot 3.5.11、Caffeine、Spring Data Redis、MyBatis、JUnit 5、Mockito、Testcontainers、Apache JMeter 5.6.3

**Spec:** `docs/后续demo0优化总方案.md` §1，以及本计划第 2 节已确认的业务与一致性边界。

## Global Constraints

- 默认工作目录为 `demo0/`，只有 Git 命令回到仓库根目录执行。
- 严格按任务顺序实施；每个任务先写失败测试，再做最小实现，再运行指定验证。
- MySQL 是事实源；HTTP 契约、权限策略、事务与 Outbox 语义保持不变。
- L1 TTL 为 10 秒；L2 正常值 TTL 为 300 秒并加入正负 60 秒抖动；墓碑 TTL 固定为 360 秒。
- 第一阶段不引入分布式锁、Redis Pub/Sub 和额外版本 Key。
- API 与压测执行结果只写入 `docs/api-test/RESULTS.md`。

## 1. 目标与范围

为 `GET /search/trending` 建立 Caffeine L1、Redis L2、MySQL/现有排行数据源三级读取链路，在不改变 HTTP 契约的前提下降低重复聚合开销，并确保内容治理和账号状态变化后缓存能够在事务提交后失效。

本轮只处理热榜聚合，不改首页热门 Feed、帖子详情、Feed 作者信息、认证链路、热度公式、MQ 更新机制和前端页面。

依据：

- `AGENTS.md`：MySQL 是事实源；遵循既有技术分层；新代码用构造器注入；外部缓存失败必须显式降级；事务副作用必须在提交后执行；结果只写入 `docs/api-test/RESULTS.md`。
- `docs/后续demo0优化总方案.md`：全站读路径多级缓存的第一阶段。
- 已对齐决策：L1 10 秒；L2 基础 300 秒并加入正负 60 秒抖动；第一阶段不做分布式锁和 Pub/Sub。

## 2. 不可改变的业务契约

- 路径仍为 `GET /search/trending`，保持公开访问策略和 `SearchTrendingVO` 字段不变。
- MySQL 与现有排行数据仍是事实来源，缓存不能成为唯一数据源。
- 读取顺序固定为 L1 -> L2 -> 数据构建器。
- Redis 读取、反序列化、排行读取、写入或删除失败时，记录带缓存 Key 和阶段的告警，并回退事实源；不得把基础设施异常伪装成空榜单。
- MySQL 查询失败继续沿用现有异常链路，不吞异常、不返回伪造成功。
- Redis 故障时允许把数据库构建结果放入当前实例 L1 10 秒，避免持续击穿数据库。
- 内容审核通过/下线、用户删除帖子、管理员删除帖子、账号封禁/解封，在业务事务提交后清理当前实例 L1，并把 L2 原子替换为唯一失效墓碑；事务回滚不得失效缓存。
- 点赞、关注和搜索热度变化不主动驱逐，依赖 TTL 最终刷新。
- 多实例场景不广播 L1 失效；其他实例最多保留 10 秒旧值，这是第一阶段明确接受的一致性边界。
- Redis 可用时，失效墓碑与条件回填必须保证旧 loader 不能重新写入 L2；不得接受 300～360 秒的 stale-backfill 窗口。
- 已经在失效前读到旧 L2 或旧 MySQL 快照的在途请求可以返回旧结果，并最多进入该实例 L1 10 秒；这是与现有 L1 边界相同的已接受窗口。
- 若事务提交时 Redis 不可用，墓碑无法写入，恢复后原 L2 最多可能存活到其剩余 TTL（上限 360 秒）；本阶段接受这一明确降级边界，必须记录告警和运行证据，不能描述为强一致。
- 无真实关键词时保留现有默认关键词；问题或校友无数据时返回空列表，不制造假数据。

## 3. 目标设计

读取链路：

    SearchServiceImpl.getTrending()
      -> TrendingCacheService.getOrLoad(loader::load)
         -> Caffeine 命中：返回
         -> Caffeine 未命中，Redis 聚合 JSON 合法：回填 L1 并返回
         -> Redis 未命中、墓碑或 JSON 损坏：保存 observedRaw，执行 TrendingDataLoader.load()
            -> 读取现有 Redis 排行；失败时使用 MySQL 排序查询
            -> MySQL 批量查询并按排行 ID 恢复顺序
            -> Lua 仅在当前 Redis 原值仍等于 observedRaw 时写 L2
            -> 条件写失败说明期间发生失效或其他实例回填：不得覆盖当前值
            -> 返回本次结果；该在途旧结果最多进入当前实例 L1 10 秒
         -> Redis GET 不可用：执行 loader，但本次禁止写 L2，只允许写 L1

写后失效链路：

    业务写事务成功
      -> TrendingCacheInvalidator.evictAfterCommit(reason)
      -> TransactionSynchronization.afterCommit()
      -> 先清当前 JVM L1
      -> SET search:trending:all __INVALIDATED__:<UUID> EX 360

当调用方不在事务中时，失效器立即执行。Redis 的 Lua 条件写和墓碑 `SET` 都是原子命令：若回填先发生，随后墓碑覆盖它；若墓碑先发生，旧 loader 的 compare-and-set 失败。Redis 失效写失败时记录 `reason`、缓存 Key 和阶段，由原 L2 剩余 TTL 自愈。

## 4. 文件清单

新增：

- `src/main/java/com/quanta/demo0/service/TrendingCacheService.java`
- `src/main/java/com/quanta/demo0/service/Impl/TrendingCacheServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/TrendingDataLoader.java`
- `src/main/java/com/quanta/demo0/service/Impl/TrendingCacheInvalidator.java`
- `src/main/resources/lua/trending-cache-compare-set.lua`
- `src/test/java/com/quanta/demo0/service/Impl/TrendingCacheServiceImplTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/TrendingCacheRedisIntegrationTests.java`
- `src/test/java/com/quanta/demo0/service/Impl/TrendingDataLoaderTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/SearchServiceImplTrendingTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/TrendingCacheInvalidatorTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentExposureServiceImplTrendingCacheTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentAuditServiceImplTrendingCacheTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentServiceImplTrendingCacheTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/AdminContentServiceImplTrendingCacheTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/AdminUserServiceImplTrendingCacheTest.java`
- `perf/trending-cache.jmx`
- `perf/summarize-trending-results.ps1`

修改：

- `pom.xml`
- `src/main/java/com/quanta/demo0/properties/SearchTrendingProperties.java`
- `src/main/resources/application.yml`
- `src/main/java/com/quanta/demo0/service/Impl/SearchServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/ContentExposureServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/ContentAuditServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/ContentServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/AdminContentServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/AdminUserServiceImpl.java`
- `.gitignore`
- `docs/api-test/RESULTS.md`
- `docs/后续demo0优化总方案.md`

不创建新的 `cache` 或 `service/trending` 包，避免同一模块出现第二套组织方式。不创建单独的性能报告；详细压测结果直接写入唯一结果文件 `docs/api-test/RESULTS.md`。

## 5. 固定配置与接口

`SearchTrendingProperties` 新增配置：

- `l1TtlSeconds = 10`
- `l1MaximumSize = 1`
- `redisTtlSeconds = 300`
- `redisTtlJitterSeconds = 60`

Redis 仍只使用一个业务 Key：`search:trending:all`。它只有两种合法状态：

- 正常 JSON：序列化后的 `SearchTrendingVO`，TTL 为 240～360 秒。
- 失效墓碑：`__INVALIDATED__:<UUID>`，TTL 固定为 360 秒；读取时视为未命中，但必须作为条件回填的 observedRaw。

不另建 generation/version Key。正常未命中以“当前 Key 不存在”为观察状态；墓碑或损坏 JSON 以完整原始字符串为 observedRaw。Redis GET 抛异常时观察状态为 unavailable，该次加载禁止回填 L2。

`TrendingCacheService` 接口固定为：

    SearchTrendingVO getOrLoad(Supplier<SearchTrendingVO> loader);
    void evict();

`TrendingDataLoader` 对外只暴露：

    SearchTrendingVO load();

`TrendingCacheInvalidator` 对外暴露：

    void evictAfterCommit(String reason);

TTL 计算为 `[240, 360]` 秒的闭区间，并保证最终值大于零。缓存 JSON 继续使用该接口既有的 Fastjson 序列化，保证已存在的正常 JSON 可以直接读取；新增测试必须先写入旧实现产生的 Fastjson JSON，再由新缓存读取。旧配置 `cache-ttl` 和 Java 字段 `cacheTtl` 同步删除，明确迁移为 `redis-ttl-seconds`，不保留两个含义重叠的配置入口。

条件回填脚本保存为 `src/main/resources/lua/trending-cache-compare-set.lua`，按项目 `RateLimitServiceImpl` 的既有模式通过 `DefaultRedisScript<Long>` 加载。脚本固定使用以下语义，其中 `ARGV[1]` 为 `ABSENT` 或 `MATCH`，`ARGV[2]` 为 observedRaw，`ARGV[3]` 为新 JSON，`ARGV[4]` 为 TTL 秒：

    local current = redis.call('GET', KEYS[1])
    if ARGV[1] == 'ABSENT' then
      if current then return 0 end
    elseif current ~= ARGV[2] then
      return 0
    end
    redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[4])
    return 1

返回 `1` 表示回填成功；返回 `0` 表示观察后 Key 已变化，调用方只记录 debug 信息，不能重试覆盖。该脚本与墓碑 `SET` 在 Redis 中串行原子执行，因此不存在“比较通过后、写入前又发生失效”的空隙。

## 6. 压测证据标准

压测是本改造的验收证据，不以“接口能返回 200”代替性能验证。基线与改造后必须在同一台机器、同一 JDK、同一 JMeter 版本、同一数据集和相同依赖状态下执行。

固定负载：

- 工具：Apache JMeter 5.6.3，非 GUI 模式。
- 请求：`GET http://127.0.0.1:9191/search/trending`。
- 断言：HTTP 200，响应体业务码为 200。
- 预热：20 线程、10 秒启动、30 秒持续；预热结果不计入统计。
- 正式轮次：20 线程、10 秒启动、60 秒持续。
- 基线连续执行 3 轮；改造后连续执行 3 轮；轮次之间确认错误率为零并等待 15 秒。
- 场景固定为“热缓存稳态”：每组先执行一次 30 秒预热，此后保留同一个 `search:trending:all`；候选组在预热后及各轮之间等待 15 秒，使 10 秒 L1 在每个正式轮次开始时均为冷、L2 为热。基线与候选都不得在三轮中途手工删除 L2。
- 比较值使用三轮中位数，不能只挑最好的一轮。

每一轮必须记录以下字段，任何字段不得留空：

- 场景和轮次；代码提交 SHA；工作树是否干净。
- Windows 版本、CPU、内存、JDK、JMeter、Spring profile。
- MySQL、Redis、应用端口与健康状态；测试数据规模。
- 样本数、错误数、错误率。
- 吞吐量 requests/s。
- 响应时间 min、max、mean、median、P90、P95、P99，单位毫秒。
- received KB/s、sent KB/s、实际持续时间。
- 原始 JTL 的本地相对路径和 SHA-256。

`docs/api-test/RESULTS.md` 中必须包含：三轮基线明细、基线中位数、三轮改造后明细、改造后中位数、绝对差值、百分比变化、功能降级矩阵和结论。不得只写“性能提升明显”。原始 JTL 和 HTML 报告放在被 `.gitignore` 排除的 `perf/results/`，不提交仓库；结果文件保留数据与哈希，便于核对。

若环境或依赖导致数据不可比较，结果状态写 `PARTIAL` 或 `BLOCKED` 并说明原因；不得使用项目未定义的 `PERF_INCONCLUSIVE` 状态，也不得编造数据。单机结果只用于前后对比，不宣称生产容量。

## 7. 实施任务

### Task 0：实施前约束确认

- [ ] 完整阅读 `AGENTS.md` 的第 0、5、6 节，以及 `docs/api-test/TEST_PLAN.md`、`docs/outbox-plan.md`、`docs/security-governance-plan.md` 中与缓存、事务提交和结果记录有关的内容。
- [ ] 在仓库根目录执行 `git status --short`，记录用户已有改动；后续提交只暂存本任务明确列出的文件。
- [ ] 检查目标 Service 的现有注入、事务、日志和测试写法；新增类使用构造器注入，不顺手重构既有类。
- [ ] 确认本机 JDK、Maven、Docker、JMeter、MySQL、Redis 和应用端口；不可用项在开始前记入 `docs/api-test/RESULTS.md`，不得到收尾时才隐瞒阻塞。

### Task 1：建立可重复压测基线

文件：`perf/trending-cache.jmx`、`perf/summarize-trending-results.ps1`、`.gitignore`、`docs/api-test/RESULTS.md`。

- [ ] 在 JMX 中用属性 `host`、`port`、`threads`、`ramp`、`duration` 控制场景，默认值分别为 `127.0.0.1`、`9191`、`20`、`10`、`60`。
- [ ] 添加 HTTP 状态断言和业务码正则断言；连接和响应超时均设为 3000 ms。
- [ ] 在 `.gitignore` 加入 `/perf/results/`，确认 `git check-ignore perf/results/probe.jtl` 返回该规则。
- [ ] 编写汇总脚本：逐个读取 HTML 报告的 `statistics.json` 中 `Total` 节点，输出样本数、错误率、吞吐、min/max/mean/median、`pct1ResTime`、`pct2ResTime`、`pct3ResTime`、收发速率；同时读取对应 JTL 的 SHA-256。
- [ ] 先执行一次 30 秒预热，再执行 `baseline-r1`、`baseline-r2`、`baseline-r3` 三轮正式测试；每轮使用独立 JTL 和 HTML 目录。
- [ ] 把环境、数据规模和三轮完整数字写入 `docs/api-test/RESULTS.md` 的 S-05 扩展小节；保留原有功能用例状态，另设“热榜缓存性能验收”状态为 `PARTIAL`，注明需等待候选版本完成比较。
- [ ] 运行 `powershell -ExecutionPolicy Bypass -File perf/summarize-trending-results.ps1 -ResultRoot perf/results -Variant baseline`，人工核对 CSV/Markdown 输出与 JMeter `statistics.json` 一致。

验证命令：

    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -Jduration=30 -l perf/results/warmup.jtl
    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -l perf/results/baseline-r1.jtl -e -o perf/results/baseline-r1-report
    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -l perf/results/baseline-r2.jtl -e -o perf/results/baseline-r2-report
    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -l perf/results/baseline-r3.jtl -e -o perf/results/baseline-r3-report

Git（仓库根目录）：

    git add demo0/perf demo0/.gitignore demo0/docs/api-test/RESULTS.md
    git commit -m "test(search): capture trending performance baseline"

### Task 2：用单元测试定义 L1/L2 行为

文件：`pom.xml`、`SearchTrendingProperties.java`、`TrendingCacheService.java`、`TrendingCacheServiceImpl.java`、`TrendingCacheServiceImplTest.java`、`application.yml`。

- [ ] 先写 `TrendingCacheServiceImplTest`，覆盖 L1 命中不访问 Redis、L2 命中回填 L1、双层未命中只调用 loader 一次、脏 JSON回退 loader、TTL 位于 240～360 秒、并发同 JVM 只执行一次加载。
- [ ] 增加 `staleLoaderCannotOverwriteInvalidationTombstone`：用两个 `CountDownLatch` 让 loader 在取得旧数据库快照后暂停，调用 `evict()` 写入墓碑，再释放 loader；断言 Lua 返回 0，Redis 仍是同一墓碑而不是旧 JSON。
- [ ] 增加反向时序测试：先允许条件回填成功，再调用 `evict()`；断言最终 Redis 值为新墓碑，证明竞态的两种排序都安全。
- [ ] 增加墓碑回填测试：loader 观察墓碑并读取新数据库值，只在墓碑未变化时以正常 JSON 替换它；墓碑被第二次失效替换时，第一次 loader 条件写必须失败。
- [ ] 增加 Redis GET 异常测试：loader 正常返回并进入 L1，但不得调用 Lua 写回；Redis SET 墓碑失败只记录告警，不伪装成成功删除。
- [ ] 运行 `mvn -Dtest=TrendingCacheServiceImplTest test`，确认因类和接口不存在而失败。
- [ ] 在 `pom.xml` 加入 `com.github.ben-manes.caffeine:caffeine`，版本交给 Spring Boot BOM。
- [ ] 新增接口、Lua 资源与实现；新类使用构造器注入。Caffeine 最大条目数 1、写后 10 秒过期；使用一个固定逻辑 Key。
- [ ] 使用 `DefaultRedisScript<Long>` 实现上述 compare-and-set；`evict()` 先清 L1，再以随机 UUID 生成墓碑并执行单条带 TTL 的 Redis `SET`，不得退回普通 `DEL`。
- [ ] Redis/JSON 异常只在缓存边界内降级并记录异常类型、缓存 Key 和阶段；loader 抛出的数据库异常必须原样向上抛出。
- [ ] 更新 properties 与 YAML 默认值，配置校验保证 TTL 和抖动非负且抖动不会产生非正 TTL。
- [ ] 重跑目标测试，确认全部通过。

Git（仓库根目录）：

    git add demo0/pom.xml demo0/src/main/java/com/quanta/demo0/properties/SearchTrendingProperties.java demo0/src/main/resources/application.yml demo0/src/main/resources/lua/trending-cache-compare-set.lua demo0/src/main/java/com/quanta/demo0/service/TrendingCacheService.java demo0/src/main/java/com/quanta/demo0/service/Impl/TrendingCacheServiceImpl.java demo0/src/test/java/com/quanta/demo0/service/Impl/TrendingCacheServiceImplTest.java
    git commit -m "feat(search): add trending multilevel cache"

### Task 3：用真实 Redis 验证 L2

文件：`TrendingCacheRedisIntegrationTests.java`。

- [ ] 使用 Testcontainers `GenericContainer` 启动 `redis:7.2-alpine`；测试中以容器 host/port 创建并启动 `LettuceConnectionFactory`，再构造真实 `StringRedisTemplate`，不启动完整 Spring 上下文，不新增固定端口和密码。
- [ ] 覆盖真实 JSON 往返、L2 命中回填新建 L1、TTL 实测位于 240～360 秒、墓碑 TTL 接近 360 秒、损坏 JSON 在未发生失效时被合法值替换。
- [ ] 用真实 Redis 和 `CountDownLatch` 重现 stale-backfill：loader 暂停期间调用 `evict()`，释放后断言旧 JSON 不能覆盖墓碑；随后发起一次新读取，断言新数据库值可以条件替换未变化的墓碑。
- [ ] 运行 `mvn -Dtest=TrendingCacheRedisIntegrationTests test`。Docker 不可用时不得把测试改成 mock；在 RESULTS 中记为 `BLOCKED` 并保留错误证据。

Git（仓库根目录）：

    git add demo0/src/test/java/com/quanta/demo0/service/Impl/TrendingCacheRedisIntegrationTests.java
    git commit -m "test(search): verify trending cache with redis"

### Task 4：提取并验证热榜数据构建器

文件：`TrendingDataLoader.java`、`TrendingDataLoaderTest.java`。

- [ ] 测试关键词、问题、校友三个限制分别严格生效，返回数量不得超过配置。
- [ ] 测试 ZSET ID 的排序在 `selectBatchIds` 后仍被恢复，无法解析的 ID 被跳过并记录告警。
- [ ] 测试问题只包含未删除且审核通过内容，校友只包含未封禁用户。
- [ ] 测试排行不足时用 MySQL 补齐且不重复；排行 Redis 失败时从 MySQL 构建；MySQL 失败向上抛出。
- [ ] 运行 `mvn -Dtest=TrendingDataLoaderTest test`，确认 RED。
- [ ] 从 `SearchServiceImpl` 中提取最小构建逻辑；只修复目标方法内直接影响缓存正确性的越界和顺序问题，不扩展到其他搜索逻辑。
- [ ] 重跑测试，确认 GREEN。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/TrendingDataLoader.java demo0/src/test/java/com/quanta/demo0/service/Impl/TrendingDataLoaderTest.java
    git commit -m "refactor(search): extract trending data loader"

### Task 5：让搜索服务只负责编排

文件：`SearchServiceImpl.java`、`SearchServiceImplTrendingTest.java`。

- [ ] 测试 `getTrending()` 仅调用 `trendingCacheService.getOrLoad(trendingDataLoader::load)`，并原样返回缓存层结果。
- [ ] 测试缓存层抛出的事实源异常没有被转换成空成功结果。
- [ ] 运行目标测试确认 RED，再做最小接线；不改 Controller 和 VO。
- [ ] 运行 `mvn -Dtest=SearchServiceImplTrendingTest,TrendingCacheServiceImplTest,TrendingDataLoaderTest test`。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/SearchServiceImpl.java demo0/src/test/java/com/quanta/demo0/service/Impl/SearchServiceImplTrendingTest.java
    git commit -m "refactor(search): route trending reads through cache"

### Task 6：建立事务提交后的统一失效器

文件：`TrendingCacheInvalidator.java`、`TrendingCacheInvalidatorTest.java`。

- [ ] 测试无事务时立即 `evict()`：当前 JVM L1 被清理，L2 被单条 `SET` 原子替换为带 360 秒 TTL 的唯一墓碑。
- [ ] 测试事务进行中不立即失效，提交后恰好写入一次墓碑。
- [ ] 测试事务回滚既不清 L1，也不改写 L2。
- [ ] 测试日志带 `reason`，但不包含敏感用户信息。
- [ ] 运行目标测试确认 RED，实现后重跑确认 GREEN。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/TrendingCacheInvalidator.java demo0/src/test/java/com/quanta/demo0/service/Impl/TrendingCacheInvalidatorTest.java
    git commit -m "feat(search): evict trending cache after commit"

### Task 7：接入并验证内容审核状态变化

文件：`ContentExposureServiceImpl.java`、`ContentAuditServiceImpl.java`、`ContentExposureServiceImplTrendingCacheTest.java`、`ContentAuditServiceImplTrendingCacheTest.java`、`AdminContentServiceImplTrendingCacheTest.java`。

- [ ] 在 `ContentExposureServiceImpl` 测试通过曝光和驳回下线都会调用 `evictAfterCommit()`；这里只验证副作用入口，不把这个无事务、吞降级异常的类误当成数据库成功点。
- [ ] 在 `ContentAuditServiceImpl.approveContent(Long)` 测试 `updateAuditStatusIfPending()` 返回 1 后走曝光并注册提交后失效；返回 0 或抛异常时不注册失效。
- [ ] 在 `AdminContentServiceImpl.audit(ContentAuditDTO)` 测试管理端审核成功路径注册提交后失效，事务回滚不失效。
- [ ] 保持三个类原有事务边界、返回值、Outbox 和曝光降级语义不变，只增加统一失效调用。
- [ ] 运行 `mvn -Dtest=ContentExposureServiceImplTrendingCacheTest,ContentAuditServiceImplTrendingCacheTest,AdminContentServiceImplTrendingCacheTest,TrendingCacheInvalidatorTest test`。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/ContentExposureServiceImpl.java demo0/src/main/java/com/quanta/demo0/service/Impl/ContentAuditServiceImpl.java demo0/src/test/java/com/quanta/demo0/service/Impl/ContentExposureServiceImplTrendingCacheTest.java demo0/src/test/java/com/quanta/demo0/service/Impl/ContentAuditServiceImplTrendingCacheTest.java demo0/src/test/java/com/quanta/demo0/service/Impl/AdminContentServiceImplTrendingCacheTest.java
    git commit -m "feat(search): evict trending cache on exposure changes"

### Task 8：接入用户删除帖子

文件：`ContentServiceImpl.java`、`ContentServiceImplTrendingCacheTest.java`。

- [ ] 测试删除成功注册提交后失效。
- [ ] 测试无权限、内容不存在、`softDeleteContent()` 抛异常和事务回滚均不失效；该 Mapper 当前返回 `void`，不得虚构“更新返回 0”分支。
- [ ] 不改变删除接口异常和 Outbox 行为。
- [ ] 运行 `mvn -Dtest=ContentServiceImplTrendingCacheTest,TrendingCacheInvalidatorTest test`。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/ContentServiceImpl.java demo0/src/test/java/com/quanta/demo0/service/Impl/ContentServiceImplTrendingCacheTest.java
    git commit -m "feat(search): evict trending cache on content deletion"

### Task 9：接入管理员删除帖子

文件：`AdminContentServiceImpl.java`、`AdminContentServiceImplTrendingCacheTest.java`。

- [ ] 测试管理员删除成功注册提交后失效，失败和回滚不失效。
- [ ] 不改变管理员权限、审计记录或 Outbox 语义。
- [ ] 运行 `mvn -Dtest=AdminContentServiceImplTrendingCacheTest,TrendingCacheInvalidatorTest test`。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/AdminContentServiceImpl.java demo0/src/test/java/com/quanta/demo0/service/Impl/AdminContentServiceImplTrendingCacheTest.java
    git commit -m "feat(search): evict trending cache on admin deletion"

### Task 10：接入账号封禁与解封

文件：`AdminUserServiceImpl.java`、`AdminUserServiceImplTrendingCacheTest.java`。

- [ ] 分别测试封禁和解封成功注册提交后失效。
- [ ] 分别测试现有 `banUser()` / `unbanUser()` 成功返回时注册提交后失效，以及 `updateById()` 抛异常、用户不存在和事务回滚时不失效；本阶段不额外引入“状态未变化短路”业务规则。
- [ ] 不改变角色、校友认证、会话失效和安全审计现有语义。
- [ ] 运行 `mvn -Dtest=AdminUserServiceImplTrendingCacheTest,TrendingCacheInvalidatorTest test`。

Git（仓库根目录）：

    git add demo0/src/main/java/com/quanta/demo0/service/Impl/AdminUserServiceImpl.java demo0/src/test/java/com/quanta/demo0/service/Impl/AdminUserServiceImplTrendingCacheTest.java
    git commit -m "feat(search): evict trending cache on account status changes"

### Task 11：分层验证故障边界

- [ ] 运行全部热榜目标测试，确认缓存、构建、编排和四类失效路径共同通过。
- [ ] 运行 `mvn test`，记录测试总数、失败数、错误数、跳过数和耗时。
- [ ] 启动真实应用，调用 S-05，核对 HTTP 200、业务码 200、字段结构和数量上限。
- [ ] 连续调用验证 L1；等待超过 10 秒后验证 L2 回填；删除 L2 后验证事实源重建和条件回填。
- [ ] 向 L2 写入损坏 JSON，验证接口仍成功、告警日志存在，并且只有原始损坏值未变化时才被合法值替换。
- [ ] 暂停 Redis，验证 MySQL 回退与 10 秒 L1 保护；恢复 Redis，验证后续请求重新写入 L2。
- [ ] 分别执行审核变化、用户删除、管理员删除、账号封禁/解封，验证提交后当前实例 L1 被清理、L2 变成墓碑；制造一次回滚，验证两层缓存均未变化。
- [ ] 执行真实 Redis 竞态测试：暂停旧 loader、提交一笔会失效热榜的业务写、再释放 loader；断言旧响应至多影响在途请求/本实例 L1 10 秒，Redis 中不得出现旧 JSON，下一次新 loader 能以新数据替换墓碑。
- [ ] 在 Redis 不可用期间提交一次治理写，再恢复 Redis；将“旧 L2 可能存活到剩余 TTL，上限 360 秒”作为已接受降级边界写入 RESULTS，不得将此场景判为强一致 PASS。
- [ ] 将每项证据写入 RESULTS 的功能降级矩阵，明确 `PASS/FAIL/BLOCKED/PARTIAL`，不得用监听端口代替真实请求证据。

命令：

    mvn -Dtest=TrendingCacheServiceImplTest,TrendingCacheRedisIntegrationTests,TrendingDataLoaderTest,SearchServiceImplTrendingTest,TrendingCacheInvalidatorTest,ContentExposureServiceImplTrendingCacheTest,ContentAuditServiceImplTrendingCacheTest,ContentServiceImplTrendingCacheTest,AdminContentServiceImplTrendingCacheTest,AdminUserServiceImplTrendingCacheTest test
    mvn test

### Task 12：采集候选数据并完成结果收口

- [ ] 确认应用、MySQL、Redis和数据规模与基线一致；若不一致，重新采集基线，不能直接比较。
- [ ] 执行一次 30 秒预热，等待 15 秒使 L1 过期但保留 L2，再执行 `candidate-r1`、`candidate-r2`、`candidate-r3` 三轮正式测试；轮间同样等待 15 秒，严格复现基线的热 L2 场景。
- [ ] 用汇总脚本提取所有字段，逐项与 JMeter HTML 报告核对。
- [ ] 在 `docs/api-test/RESULTS.md` S-05 下写入三轮候选明细、中位数、基线与候选差值、百分比变化、JTL SHA-256 和单机结论。
- [ ] 分别维护 S-05 功能状态与“热榜缓存性能验收”状态；功能正确但性能无法比较时，性能状态用 `PARTIAL`，功能失败用 `FAIL`，环境不可用用 `BLOCKED`。
- [ ] 更新 `docs/后续demo0优化总方案.md`：只写阶段状态、已验证边界、遗留限制和指向 `docs/api-test/RESULTS.md` 的结果引用，不复制压测数字。
- [ ] 运行 `git diff --check`，确认无空白错误；运行 `git status --short`，确认未纳入 `perf/results/` 和无关文件。

命令：

    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -Jduration=30 -l perf/results/candidate-warmup.jtl
    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -l perf/results/candidate-r1.jtl -e -o perf/results/candidate-r1-report
    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -l perf/results/candidate-r2.jtl -e -o perf/results/candidate-r2-report
    C:\Users\dwc12\Desktop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t perf/trending-cache.jmx -l perf/results/candidate-r3.jtl -e -o perf/results/candidate-r3-report
    powershell -ExecutionPolicy Bypass -File perf/summarize-trending-results.ps1 -ResultRoot perf/results -Variant candidate

Git（仓库根目录）：

    git add demo0/docs/api-test/RESULTS.md demo0/docs/后续demo0优化总方案.md
    git commit -m "docs(search): record trending cache verification"

### Task 13：独立复核

该改造横跨缓存一致性、事务提交回调和多个业务写路径，按 `AGENTS.md` 必须经过独立复核。

- [ ] 复核者检查接口契约、MySQL 事实源、Redis 降级、事务提交/回滚边界、多实例 10 秒窗口、stale-backfill 防护和四条写路径。
- [ ] 复核者手工推演竞态的两种原子顺序：Lua 回填先于墓碑、墓碑先于 Lua 回填；任一顺序都必须以墓碑或更新后的正常值结束，不能以旧 JSON 结束。
- [ ] 复核者抽查至少一轮基线与一轮候选的 `statistics.json`、JTL SHA-256 和 RESULTS 表格，确认数字没有手工转录错误。
- [ ] 对发现的问题先补失败测试再修复，重跑受影响测试与相应压测轮次。
- [ ] 在 `docs/api-test/RESULTS.md` 记录复核日期、范围、发现项、修复提交和最终结论。

## 8. 最终验收门禁

以下条件必须全部满足，才能标记本阶段完成：

- HTTP 契约与权限策略未变化。
- L1、L2、事实源、脏 JSON、Redis 故障的测试和真实运行证据齐全。
- stale-backfill 的两种竞态顺序均有单元测试，且至少一项真实 Redis 集成测试证明旧 loader 无法覆盖墓碑。
- 排行顺序、过滤、补齐、数量上限均有测试。
- 四类治理写路径均证明提交后失效、回滚不失效。
- 真实 Redis 集成测试通过；若因 Docker 阻塞，阶段不能记完全完成。
- 全量 `mvn test` 通过，或将无关既有失败单独记录并提供证据。
- 三轮基线和三轮候选详细数据均写入 `docs/api-test/RESULTS.md`，包含环境、全部统计字段、哈希、中位数和差值。
- 原始性能产物未提交，用户无关改动未被覆盖。
- 独立复核完成且无未解决的高优先级问题。

## 9. 回滚与明确不做

本改造不涉及数据库迁移、消息格式和 API 变更。回滚时按提交逆序撤销四类写后失效、搜索编排和缓存实现即可；正常 JSON 与墓碑都可直接删除并由事实源重建。

本阶段明确不做：分布式锁、Pub/Sub、本地缓存跨实例广播、后台主动刷新、Micrometer/Prometheus 看板、首页热门 Feed、帖子详情、Feed 作者缓存和认证缓存。这些项目需在本阶段证据通过后单独立项。
