# demo0 后续优化总方案

> 定位：后续改造的**总索引**。每个改造项只记结论要点（是什么、为什么、怎么做、优先级），细节讨论一項、展开一項。
> 前置参考：《主服务简历亮点与增强计划》《项目当前问题与改进方向》。

---

## 改造项总览

| # | 改造项 | 一句话结论 | 优先级 |
|---|---|---|---|
| 1 | 全站读路径多级缓存 | Caffeine+Redis 挂在真实读路径上（认证/详情/作者/热榜），三件套各有本项目真实场景 | 高 |
| 2 | Feed 大 V 扇出问题 | 纯写扩散随粉丝数线性膨胀，pipeline 缓解 + 推拉分级收口 | 高（方案先行） |
| 3 | 推荐流个性化改造（热度流 + 画像推荐流） | 新增 per-user 流：画像重排 + LLM 标签 + Agent 显式信号，画像存 Redis Hash | 高 |
| 4 | ES 8 升级 | RestHighLevelClient 已废弃 + Boot 3.5 错代组合，一次性还债 | 中 |
| 5 | 向量库替换 | SimpleVectorStore 五缺陷确认要换；候选 Redis Stack / ES 合并 / Qdrant，暂不拍板 | 中 |
| 6 | 可观测性 | Micrometer+Prometheus+Grafana：异步架构没指标=黑盒，缓存命中率/积压深度/P99 的证据链 | 高 |
| 7 | 真实压测（JMeter） | 点赞/Feed/详情改造前后各压一轮，所有改造项的数字证据链 | 高 |
| 8 | 接口防重复提交（幂等 Token） | HTTP 入口侧幂等空白（现有幂等全在 MQ 消费侧），Redis SETNX 原子占位 | 高 |
| 9 | 私信 + 群聊 | WebSocket/Outbox 基建已就绪的填空；**待定**——小程序场景用户少聊天，后续再决策 | 待定 |
| 10 | 定时任y务多实例防重 | 现有任务（对账/热度重算）多实例重复跑的真 bug；轻量锁 + 幂等语义，**不做框架** | 中 |
| 11 | 慢 SQL 治理 + 索引优化 | 搭压测的车：slow log 找慢查询，EXPLAIN 前后对比 | 中 |
| 12 | 按域分包重构（package-by-feature） | 单模块模块化单体；迁包、大类拆分和架构门禁已实现；验收状态见执行计划及 API 结果 | 中 |

### 当前实施状态（2026-09-28）

- 第 1 项中的“热榜聚合”已完成代码、真实 Redis 竞态、事务提交后失效、接口运行和全量回归；详细实现与证据见 `docs/plans/2026-09-20-trending-multilevel-cache.md` 和 `docs/api-test/RESULTS.md` 的 S-05 扩展小节。
- 性能验收暂记 `PARTIAL`：候选三轮吞吐中位数未优于基线，用户决定本轮跳过性能优化与复测；不得在简历或说明中写成“QPS 提升”。
- 帖子详情、Feed 作者信息和认证链路代码已落地，统一计划见 [认证、帖子详情与 Feed 作者读路径缓存](plans/2026-09-20-auth-detail-feed-read-cache.md)。非压测功能验收已完成，状态为 `PASS`，分层证据、全量回归与限制统一见 [RESULTS.md 的 S-RC 小节](api-test/RESULTS.md#read-path-cache)；性能按用户 2026-09-28 指示移入 [第 7 项后续压测计划](#jmeter-load-testing)，保持 `PENDING`，不再阻塞本项功能收口。
- 第 3 项推荐流个性化的 ①画像 Hash+行为信号、②重排进 recommend() 已完成（2026-09-24，scene 语义按流形态决策收敛：latest→画像流、新增 recommend 显式参数、hot 纯热度序取消曝光去重），真栈验收/全量回归/独立审查证据见 `docs/api-test/RESULTS.md` 的「推荐流个性化（S-PF）」小节；③④已于 2026-09-28 立项并实施，范围和验收分别见 `docs/plans/2026-09-28-recommend-topics-preferences.md`、`docs/api-test/RESULTS.md` 的 S-TP 小节。存量全量回填不属于已执行范围。

明确排除：网关/注册中心（单体上微服务组件是负面信号）、DDD、秒杀（原计划已论证）、主服务堆 AI 功能（模糊 Java 后端定位）。

（后续讨论的改造项按顺序追加）

---

## 1. 全站读路径多级缓存

**是什么**：四个真实读路径按各自一致性边界缓存：认证安全快照、Feed 作者信息采用 Caffeine 本地缓存；帖子详情和热榜聚合采用 Caffeine + Redis 两级缓存。实现与验收分别以专项计划、`docs/api-test/RESULTS.md` 为准。

**为什么**：缓存减少真实读路径中重复的稳定信息查询。认证命中后仍逐次校验 JWT、Redis 会话和封禁标记；详情命中后仍处理访问者高亮和浏览历史；Feed 只缓存作者信息，不缓存整页结果。MySQL 保持事实源地位，本地缓存通过短 TTL 与当前实例失效限制陈旧窗口，详情 Redis 通过条件回填与失效墓碑阻止旧 Loader 回填。边界（不加，含理由）：限流/曝光去重/Inbox 状态机（需要跨实例精确或事务一致）、ES 搜索结果（重复缓存收益小、复杂度高）、管理端列表（QPS 低）。

**怎么做（要点，按优先级）**：
- 落点④ **认证链路用户信息（已实现）**：`TokenAuthenticationServiceImpl` 使用 `AuthenticationSnapshotCacheImpl` 缓存用户、身份与角色安全快照；普通/Service Token 按 Key 隔离，命中后不再查询这三类 MySQL 信息，JWT、会话与封禁判断仍逐次执行。用户安全信息变更后由统一失效器在事务提交后清理当前实例；其他实例陈旧边界见专项计划。
- 落点① **帖子详情（已实现）**：`ContentDetailCacheServiceImpl` 缓存与访问者无关的内容/图片快照；正常值 TTL 抖动、负缓存、Lua 条件回填与唯一墓碑均已落地。内容、审核与计数写路径通过 `ContentDetailCacheInvalidatorImpl` 在事务提交后清 L1、写 Redis 墓碑；访问者高亮和浏览历史仍逐次处理。
- 落点② **Feed 装配作者信息（已实现）**：`AuthorProfileCacheImpl.getAll()` 批量读取作者，本地全命中不查作者 Mapper，部分未命中合并为一次批量 SQL。改造前本就一页一次批量作者查询，收益是跨请求复用，不能写成“20 次 SQL 降为 0”；作者资料变更由统一失效器清理当前实例。
- 落点③ 热榜聚合：Caffeine 5-10s，全站一个 key 命中率极高，顺带把聚合计算挡掉；不担心实例间不一致——各算各的最多差几秒；
- 三件套对应本项目的真实场景（不是教科书假设）：
  - 穿透 = Feed ZSET 与 MySQL 的**异步删除时间差窗口**：`hideRejectedContent` 走 MQ，删除消息还在重试队列时 ZSET 里留着已删的 contentId，用户点进必然 null——爬虫遍历或 Feed 积压时这些 null 请求全打 DB；方案：空值缓存（null 也写缓存，TTL 60s），防的是自己链路的时差不是黑客；
  - 击穿 = 热榜 Top1 的缓存到期瞬间，几十倍 QPS 同时穿透 DB 重建；方案：**逻辑过期优于 singleflight**——帖子进热榜时我们恰好"知道它热"，可主动标记；值永不过期、发现逻辑过期后异步刷新当次返旧值；
  - 雪崩 = `RecommendFeedInitializer` 预热批量灌缓存若用同一固定 TTL 会集体失效；方案：TTL ±20% 随机抖动。

---

## 2. Feed 大 V 扇出问题

> **【已评估，决定不做】2026-09：校园场景没有大 V（几百粉丝封顶），纯写扩散无实际瓶颈，不为简历做架构。面试可口头讲推拉分级方案，见下文要点。**

**是什么**：`pushToFollowersFeed` / `removeFeedFromFollowers` 当前**纯推模式（写扩散）**：循环所有粉丝，每粉丝 2 次 ZADD/ZREM（all 池 + 分类池）。10w 粉丝 = 20w 次 Redis 命令，且是单条 MQ 消息在一个 consumer 里同步 for 循环执行。

**为什么**：扇出成本随粉丝数线性增长；粉丝多时这条消息处理极慢，**堵住的是整条 Feed 推送队列——延迟的不是大 V 自己，是他身后所有人的帖子推送**。诚实评估：校园规模最大 V 几百粉丝，20w 写当前不会真实发生——所以正确的姿势是分层处理，不是现在就上推拉结合。

**怎么做（要点）**：
- 方案 A 先做（半天）：循环内 Redis pipeline 批量提交（每 500 条一次），2N 次网络往返压成 N/500 次——解决"慢"，不解决"扇出本身"；
- 方案 B 设计收口（简历故事价值 > 当前必要）：推拉分级——粉丝数超阈值（如 >1000）的大 V 发帖**不写扩散**，只进全局推荐池（现有 ZSET 天然承担）；读 Feed 时拉自己收件箱（普通关注者的推）+ 实时拉大 V 发帖列表（ZREVRANGEBYSCORE）按时间归并；先出设计文档再实现；
- 面试话术："写扩散在粉丝量均匀的社区是低延迟最优解，但扇出成本随粉丝数线性增长，所以我按粉丝规模做推拉分级——头部走读时拉取合并，腰尾部走写扩散"；
- 提及不实现：活跃粉丝才推、僵尸粉不推（需活跃度数据，偏重）。

**与第 1 项的关系**：缓存是 Feed **读侧**的事，本项是 Feed **写侧**的事——合起来是"Feed 流读写两端改造"的完整故事，比单独讲"加了 Caffeine"完整。

---

## 3. 推荐流个性化改造（热度流 + 画像推荐流）

> **【流形态决策】2026-09 调整：不新增第三条流，而是"两条流"——**
> - **时间流（scene=latest）删除**：不再作为独立 scene 暴露，首页时间 tab 下线。但 `content:recommend:all`（时间戳 ZSET）及写入链路**保留**，降级为画像流的召回源之一（新帖冷启动保底：新帖热度 0 分，若画像流只从 hot 池召回将永远没有入口，生态僵化）；
> - **热度流（scene=hot）保留**，**取消其曝光去重**（`recommend:exposed:` 对 hot 场景不再读写）——热度分本身随时间衰减流动，重复感有限，去重机制降级为画像流专用；
> - **画像流**：登录用户访问时走画像重排路径，未登录/新用户 α 权重拉满退化为热度兜底。**画像流的曝光去重必须保留**——画像分一天内几乎不变，无去重则用户每次下拉刷到同一批帖，这是个性化流的经典体验灾难；
> - 首页从"最新/热门"切换改为"热门/为你推荐"语义（**涉及前端，后续统一改造**：`pages/home` 的 scene 切换、`content.service` 的 buildRecommendQuery、mock 池 RecommendScene 类型均留待前端改造批次一并处理，后端接口先行兼容）。

**是什么**：现有 `recommend()` 已有 latest/hot 两流但全站统一；按上述决策收敛为"热度流 + per-user 画像流"——`finalScore = α·热度分 + β·标签匹配分 + γ·显式偏好`，α 随用户行为量动态调整（新用户偏热度=冷启动天然解决）。简历口径写"**基于用户画像的个性化重排**"，不写"推荐算法/推荐系统"。

**为什么**：工业推荐链路"召回→精排→重排"的简化版——召回 = hot 池 ∪ latest 池（时间分兜底）+ 曝光过滤（画像流专用），重排用画像加权。数据规模撑不起协同过滤（行为稀疏），加权重排是正解不是偷懒。删时间流的叙事更顺："个性化流的冷启动模式天然覆盖了纯时间流"。

**怎么做（要点）**：
- 画像计算与存储在**主服务侧**（推荐不依赖 Agent 在线），行为信号：浏览×1/赞×2/藏×3/评×4，带时间衰减；
- 帖子标签两类：结构化 contentType 保留；主题由审核通过事务登记 Outbox 后异步调用 LLM 打标，每帖 0–3 个受控主题，不强凑数量。当前词表 26 项，唯一来源 `src/main/resources/recommend-topics.json`；经验分享可与课程学业/求职实习等共存，运动分别打具体项目。`tags=NULL` 未处理、`[]` 已处理无命中；个人学院/年级不推断为兴趣。
- Agent 侧贡献：只同步今后的 user/feedback 记忆明确主题喜好/厌恶，不回填历史行为/记忆。Qdrant 单点持久化 pending → BOT HTTP 事实与 Outbox 同事务 → Inbox 重建独立显式 Hash；不传记忆原文，不累加重复消息。ADD/UPDATE/DELETE 支持撤销，负偏好强降权不硬屏蔽，不混入行为衰减及分母。
- 执行顺序（已按风险隔离重排）：
  1. **画像 Hash + 行为信号**（约 2 天，纯增量零风险——即使重排未上线，数据也在积累，上线时画像已热）——**已完成**；
  2. **重排进 recommend()**：latest 立即映射画像流（匿名 α=1 热度兜底，语义即刻切换），新增 `scene=recommend` 显式画像流参数；hot 保持纯热度序并取消曝光去重（曝光去重整体挪给画像流）；β 先只用结构化标签（contentType），LLM 标签没上线前 β 权重调低；API 回归用例（P4-01 等）同步改造——**已完成**；
  3. **LLM 主题标签**：增量链路与限量/可续跑回填工具已实现，小批真实验证先行；工具跑通后的全量存量回填是后续独立批量执行，不混作本次完成。标签解析仍只扩展 `UserProfileServiceImpl.resolveContentTags`，配置值以 application.yml 为准；
  4. **Agent 显式信号**：M5 收口后启动本跨项目任务，未来偏好后台同步与独立重排项已实现；不修改人格/策略提示词、不追加偏好提取 LLM 调用。验证与未执行边界以 S-TP 为准；
- **实施证据**：①②的原验收仍见「推荐流个性化（S-PF）」；③④本次实现、迁移/回滚、定向验证与未执行范围见统一 RESULTS 的 S-TP。M5 原冻结证据不充当新版本门禁。
- **前端改造（首页 tab 语义、scene 参数、mock 池）标注为后续统一批次**，不与后端各步混做，后端先保持 `scene=latest` 参数向后兼容（映射到画像流/热度兜底），前端切换后再移除兼容层。

**存储选型：为什么 Redis Hash（含落选项）**：
- 为什么 Redis 不用 MySQL：画像是**行为数据的派生快照**（事实源在 MySQL 行为表，丢了可重算，`RecommendFeedInitializer` 同款预热模式）；推荐请求每次要读、高频小值；写路径是 MQ 消费驱动的原子小更新，放 MySQL 反而是每条行为一次行锁竞争；
- 为什么 Hash 不用其他结构：
  - vs **String(JSON)**：改一个词要读整串→解析→改→序列化→写回，高并发读改写有竞态需自己加锁；Hash 的 `HINCRBYFLOAT` 单命令原子累加，单线程 Redis 下天然无竞态；
  - vs **ZSET**：加减权重得 ZSCORE→算→ZADD 三步非原子；且画像不需要"按权重排序"（匹配分在 Java 侧算），ZSET 的排序能力是白付的成本；
  - vs **Set**：存词不存权重，画像退化成关键词列表，α 混合公式没法算；
  - vs **Caffeine 本地**：画像更新频率高（每次点赞都变）且多实例要较快一致，落在"不该用本地缓存"那类（各看各的会不一致）；
  - vs **Qdrant 向量画像**：路线 C 长期可选（用户兴趣向量=近期交互帖向量加权平均），数据规模大了再上；届时标签 Hash 仍保留——可解释可调试（能答出"用户为什么看到这条帖"），向量分做补充；
  - vs **RedisJSON**：几十个字段的小数据上重武器，纯增依赖。

---

## 4. ES 8 升级

**是什么**：ES 7.12.1 + `RestHighLevelClient` → ES 8.x + 新版 `ElasticsearchClient`，只做 API 迁移不做功能翻新（搜索语义不变），估 2-3 天。

**为什么**：① `RestHighLevelClient` 官方 7.15 起废弃，越晚还债越贵；② Spring Boot 3.5 生态已整体迁 8.x 新客户端，现状是错代组合；③ 8.x 是当前主流，面试会被问"为什么用旧版"；④ 7.12 缺 kNN 向量检索等能力。

**怎么做（要点）**：所有查询构造/索引管理代码从 RestHighLevelClient 重写为 ElasticsearchClient；数据量小，索引直接重灌不做 reindex；放执行中段，不阻塞缓存/推荐改造。升级完成后再拍板第 5 项的向量库归属。

**实施状态（2026-09-26）**：**已完成**。客户端、配置、索引初始化与搜索 Service 已原位迁移到 `ElasticsearchClient 8.18.8`；一次 323 项 Maven 全量回归、日期兼容修复后的核心+Consumer 9 项定向回归及真实 ES8+IK 内容搜索通过；回答真实写删、SearchReconcile 同事件重投幂等与 RAG `enableAi=false` 真实造数也已完成真栈验收，证据见 `docs/api-test/RESULTS.md`。

---

## 5. 向量库替换（SimpleVectorStore → 待定，候选 Redis Stack / ES 合并 / Qdrant）

**是什么**：现状 Spring AI `SimpleVectorStore` 五个真实缺陷——① 全量向量驻 JVM 堆（几万条 × 1024 维 = 几百 MB，GC 抖动）；② JSON 文件 + synchronized **全量重写**（每次发帖重写全部向量，写放大严重，重启加载慢）；③ O(N) 暴力扫描无索引，召回延迟随量线性劣化；④ 无 metadata filter（不能"只搜专业区/排除已删"）；⑤ 多实例各持一份，删除不全局可见。

**为什么换、但暂不拍板**：量级（几万向量）在主流方案面前都是毫秒级，选型差异在"运维成本 + filter 灵活性 + 架构收敛"之间权衡，留到 ES 8 升级完成后决定。**已排除**：Milvus（重武器，几万向量浪费）、pgvector（为向量引 PG 不值）。

**候选（待决策）**：
- **Redis Stack（当前倾向）**：已有 Redis 零新增运维；HNSW + filter + 持久化都有，Spring AI 现成 `RedisVectorStore`；能整个删掉 `VectorStorePersistenceService` 这坨 JSON 持久化代码；多实例共享、删除全局可见。代价：Redis 变双重角色（缓存 + 向量）。
- **ES 合并**：ES 8 自带 kNN——全文 + 向量收进一家，`RagFusionService` 双路召回变单服务，中间件最少。代价：ES 向量 filter 灵活性不如专用库，向量负载和搜索负载耦合。
- **统一 Qdrant**：Agent 侧记忆库已在用 Qdrant，主服务复用则全项目一个向量库、团队熟度现成。代价：主服务新增一个独立中间件，Java↔Qdrant 链路要自己维护。

**决策时机**：第 4 项完成后，按"量级是否增长 + 想不想要中间件收敛"拍板。

---

## 6. 可观测性（Micrometer + Prometheus + Grafana）

**是什么**：接入指标体系——Inbox 积压深度、Outbox PENDING 水位、MQ 死信计数、缓存命中率、接口 P99、定时任务耗时/成败，Grafana 出看板。

**为什么**：整个系统是 Outbox/Inbox/MQ/缓存的异步架构，出问题全靠翻日志——**异步架构没有指标等于黑盒**；"你怎么知道系统是健康的"这类面试题从此有答案；缓存命中率曲线是第 1 项"缓存有效"的唯一证据。工作量 1-2 天。

**怎么做（要点）**：Spring Boot 已内置 Micrometer，加 prometheus registry 暴露 `/actuator/prometheus`；业务指标手动埋（Counter/Gauge：inbox.backlog、outbox.pending、cache.hit）；Grafana 导入 JVM + 自定义看板；与第 7 项压测联动（压测时看板实时出曲线）。
**与 Agent 侧 Langfuse 的握手（提纲，细节后定）**：两套可观测分工——Grafana 看系统健康（数值指标），Agent 侧 Langfuse 看单次对话轨迹（trace 回放）。补一根透传线即可闭环：demo0 发 bot mention 事件带 eventId/commentId，Agent 侧存进 Langfuse trace metadata，使 Grafana 告警（如 bot 拦截率尖峰）能直接跳到 Langfuse 按时间窗回放对应 trace。改动很小，具体实现后面深入。

---

<a id="jmeter-load-testing"></a>

## 7. 真实压测（JMeter）

**是什么**：对点赞接口、Feed 拉取、帖子详情跑真实压测，记录 QPS / P99；改造项完成前后各压一轮留对比数据。

**为什么**：工具已装但上次没跑出数据；它不是独立项，是**其他所有项的证据链**——多级缓存（前后对比）、大 V pipeline（扇出耗时对比）、ES 升级（迁移前后延迟）都靠它出数字。简历上"引入多级缓存"是句干话，"P99 从 X 降到 Y、命中率 Z%"才是亮点。半天到一天。

**怎么做（要点）**：JMeter 线程组模拟登录态（JWT）+ 混合场景（读为主、点赞写入混入）；使用可比基线与候选留对比数据。执行结果统一落在 `docs/api-test/RESULTS.md`，本索引不复制指标；简历只引用已验证结论。

**认证/详情/Feed 作者缓存压测（2026-09-28 从第 1 项迁入，后续执行）**：本轮先完成缓存功能验收，以下性能任务保持 `PENDING`，不阻塞第 1 项功能收口，也不表示性能提升已获验证。

| 场景 | 请求 | 默认负载 | 关键断言 |
|---|---|---:|---|
| auth | `GET /user/security-context` | 30 线程，10 秒启动，60 秒持续 | HTTP 200、业务码 200、userId/roles 存在 |
| detail | `GET /content/detail/${contentId}` | 20 线程，10 秒启动，60 秒持续 | HTTP 200、业务码 200、contentId 匹配 |
| feed | `GET /follow/feed?offset=0&pageSize=20` | 20 线程，10 秒启动，60 秒持续 | HTTP 200、业务码 200、list 存在 |

- [ ] 创建 `perf/read-path-cache.jmx` 与 `perf/summarize-read-path-results.ps1`；JMX 支持 `host`、`port`、`token`、`contentId`、`threads`、`ramp`、`duration`、`runAuth`、`runDetail`、`runFeed`，连接/响应超时均为 3000 ms。Token 仅通过命令行属性传入，不写 JMX、报告、脚本或 Git。
- [ ] 冻结可比基线与候选：同机器、JDK、JMeter、数据规模、用户/contentId、依赖与负载条件；缓存代码已经落地，不能把当前版本冒充改造前基线。以隔离检出的原始版本采样，无法重建可比条件则只报告当前容量，不宣称缓存收益。
- [ ] auth/detail/feed 各先预热 30 秒，再执行三轮 60 秒基线与三轮候选，轮次间等待 15 秒；每轮错误率为零，比较三轮中位数。
- [ ] 从 HTML 报告 `statistics.json` 的 `Total` 节点汇总环境、提交 SHA、工作树状态、样本数、错误率、吞吐、min/max/mean/median/P90/P95/P99、收发速率与实际时长；原始 JTL/报告放入已忽略的 `perf/results/`，RESULTS 保留路径和 SHA-256。
- [ ] 归档基线/候选明细、中位数、绝对与百分比变化，并核对查询调用证据：认证仍查会话/封禁，详情仍读访问者状态/写浏览历史，Feed 改造前已是一页一次批量作者 SQL。不写“详情零数据库访问”或“Feed 20 次 SQL 降为 0”。
- [ ] 独立抽查每个场景至少一轮基线与候选原始报告、JTL 哈希与 RESULTS 数字，再判定性能门禁；热榜已有性能 `PARTIAL` 与本项分别记录。

---

## 8. 接口防重复提交（幂等 Token）

**是什么**：发帖/点赞/收藏等写操作的 HTTP 入口加幂等防护：进页面发 token（Redis 记录），提交时带上，服务端 `SETNX` 原子占位，重复提交直接拒。

**为什么**：现有幂等全在 **MQ 消费侧**（Inbox），HTTP 入口侧是空白——用户双击、网络层重试就能造成重复发帖/重复点赞（虽然有唯一约束兜底，但报错体验差且消耗写路径）。"接口幂等怎么做的"是面试高频题，MQ 侧故事很强，入口侧补上就闭环。1 天。

**怎么做（要点）**：`POST /idempotency-token` 发 token → 写操作 Header 带 token → Redis `SETNX idem:{token} 1 EX 300` 占位成功才放行 → 业务异常时删除 token 允许重试；注解 + AOP 收口（复用现有 RateLimitAspect 的 AOP 模式）。

---

## 9. 私信 + 群聊（待定）

**是什么**：conversation + message 两张表的会话模型；私信用会话内 lastReadId 相减算未读（不累加计数，避免并发漂移——点赞幂等教训迁移）；群聊刻意选**读扩散**（消息写一份、成员按 lastReadId 拉取），与大 V 写扩散题凑成"读写扩散选型"一对接龙；离线消息复用 Outbox→MQ→WebSocket 链路。

**为什么待定**：WebSocket/STOMP/Outbox 基建全部就绪，技术上是填空（2-3 天/私信，+2-3 天/群聊）；**但产品判断：微信小程序场景用户很少在里面聊天**，做了使用率低。简历价值（功能完整性 + 读写扩散面试题）与产品价值不对齐，**后续再决策**。

---

## 10. 定时任务多实例防重（轻量锁，不做框架）

**是什么**：现有 `@Scheduled` 任务（ES↔MySQL 对账、热度重算、Inbox 超时锁回收）多实例下会重复执行。方案：任务执行前抢锁（DB 行锁 / Redis SETNX + TTL），抢到的跑、没抢到的跳过；锁 TTL 保证崩溃后自动可接手；OutboxDispatcher 的租约模式（lockedBy/lockedUntil）已验证，提炼为通用模板或直接用 ShedLock。

**为什么（且为什么不是"框架"）**：① 真缺口——Outbox 有租约锁，其他任务没有；**热度重算若基于上次结果累加，重复跑会算错，是真 bug**；② 自研"分布式定时任务幂等框架"是伪命题：核心就 30 行，生态位被 ShedLock（开源版正是这套锁）和 XXL-Job 占着，自研一个不如 ShedLock 的东西会被"为什么不用 ShedLock"打穿；③ 正确的面试姿势是选型判断："任务都是分钟级低频，轻量锁够用；海量任务分片调度才换 XXL-Job"——写小而真的，不写大而虚的（与拒绝 DDD 同一原则）。

**怎么做（要点）**：半天。① 各任务补幂等语义（对账/热度重算改成覆盖式写，重复执行无害）；② 套 ShedLock（注解接入）或 30 行锁模板；③ 触发时间错峰（:00 / :10）；④ 执行指标接入第 6 项看板。

---

## 11. 慢 SQL 治理 + 索引优化

**是什么**：开 MySQL slow log → 找出 Feed 装配/搜索/对账的慢查询 → 补索引或改写 SQL → EXPLAIN 前后对比留证。

**为什么**：成本极低（一天），"SQL 优化"只有配合真实执行计划对比才立得住；适合搭第 7 项压测的车——压测暴露慢查询，治理后复压验证。单独立项不值得，作为压测的后续动作执行。

**怎么做（要点）**：`long_query_time=0.1` 采样 → 挑 2-3 条真实慢查询 → EXPLAIN 前后对比 → 数据写进压测报告。

---

## 12. 按域分包重构（package-by-feature 模块化单体）

**是什么**：单 Maven 模块、单 Spring Boot 应用，顶层固定为 `content/answer/comment/interaction/feed/follow/user/identity/notification/moderation/search/rag/platform`；域内沿用 `controller/service/impl/mapper/entity/dto/vo` 等既有命名。内容、评论、回答、用户、搜索索引、审核工作流、Outbox 消息构造和 Rabbit 配置按职责拆分，不做 DDD 战术模式，不拆微服务。

**边界**：跨域通过公开 Service 和 DTO/VO/枚举/消息契约，不直接访问另一域 Mapper、Entity 或 ServiceImpl。Admin Controller 不拆；互动计数同事务同步调用 Counter Service，不新增计数事件。Feed 画像使用 `UserInterestProfileService`；用户资料使用 `UserProfileService`；安全状态、认证模型、请求上下文和事件管理分别归 `platform/security` 与 `platform/mq/admin`。

**实施与验收**：在 `codex/package-by-feature-refactor` 按“小文件迁移 → 中点原测试 → 大类拆分与最终验证”实施。详情以 [执行计划](plans/2026-09-28-package-by-feature-modular-monolith.md)、[终态包与文件清单](plans/2026-09-29-package-by-feature-final-inventory.md) 和 [API 唯一结果记录](api-test/RESULTS.md) 为准；不把编译或单元测试通过等同于 ES、OSS、AI 或 Bot 真链路验收，不做性能结论。

2026-09-29 追加补验收：ES 真实内容/回答读写、搜索过滤/高亮、重复消费和删除收敛，云文本机审、RAG 真实总结，以及 Bot 生成/写库/二次机审/公开可见性与幂等复投均通过；本机漏迁移和模型装配通过既有脚本与进程级覆盖修正，未追加改动生产源码。既有 HTTP 普通用户 fixture、ES 全量 reindex、图像审核与性能等未覆盖边界仍以 RESULTS 原样记录，未合并或 push。

---

（后续改造项追加于此，同样格式：是什么 / 为什么 / 怎么做要点）

待补充：lua总限流？推荐流语义？权限可视化？traceId（?
