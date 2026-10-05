# 首页推荐冷启动与真实曝光 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 当前技能目录没有这两个执行技能；实施时以 `demo0/AGENTS.md` 的红绿验证、逐任务执行与独立审查流程为准，不安装额外技能，不把名称当作已执行证明。用户已确认推荐选项并授权执行；使用三个实现子 agent 分工和一名独立审查者，根 agent 集中运行后端测试。

**Goal:** 首页保留“推荐／热度”，为游客和登录用户提供稳定分页、真实曝光去重及约20%近期探索，候选耗尽时提供主动再看。

**Architecture:** 保留现有热门/最新双池与个性化算分，在 feed 域增加推荐会话与曝光两个服务。会话保存稳定页面和下发记录，曝光单独保存真实可见记录；通过内容域公开查询口扩召回，每次输出仍校验 MySQL 当前可见性。小程序首页持有会话游标、观察卡片可见性并批量回传。

**Tech Stack:** 现有 Java 17、Spring Boot 3.5.11、MyBatis、MySQL、Redis、JUnit/Mockito/Testcontainers；微信原生小程序 TypeScript、IntersectionObserver、Node test。版本来自当前 pom/package，不升级依赖。

**Spec:** [首页推荐冷启动与曝光设计](2026-10-04-home-recommend-cold-start-design.md)，第1节为用户确认决策，第4节为可配置工程默认值。

## Global Constraints

### 执行记录（2026-10-04）

下方分步清单是原执行规格；本节记录实际落地与验证，未执行的发布/设备步骤不勾选为完成。

| 任务 | 实施状态 | 验证与剩余边界 |
|---|---|---|
| Task 1 协议与兼容 | 已实施 | RecommendPageVO/Visitor/Query/配置、Feed 包装与409 Advice完成；Protocol 2、配置1、旧Scene10通过 |
| Task 2 会话与真实曝光 | 已实施 | 新 v2 Redis 分区、Lua、墓碑/活跃限额/构页owner与 immutable 页元数据完成；真实 Redis 4项通过；曝光批量 score 数组读取，逐条 NX不续期 |
| Task 3 排序、探索与续扫 | 已实施 | 纯rankCandidates、跨页探索位、负偏好探索交还主推荐、ZSET 扩窗/MySQL 时间-ID续扫与 SEARCHING完成；Selector5、Session4、纯rank1、旧rank16及真实SQL通过 |
| Task 4 HTTP与安全 | 已实施 | 精确开放曝光路径、参数/归属校验、可信用户优先、过期409/繁忙429；真实controllers+MySQL/Redis通过，Security链10通过。完整真实JWT登录后的新接口联调仍PENDING |
| Task 5 首页交互 | 已实施 | 推荐/热度、游客持久化、Observer/批量队列、轮次分页、状态/主动再看、身份切换与旧hot异步响应隔离完成；新专项8项与typecheck通过，旧bot-comment9项在早期同批通过。设备观察器仍PENDING |
| Task 6 联调与收口 | 自动化联调已完成 | 隔离 MySQL8.0.43/Redis7.2 HTTP集成3项、架构5项通过。backend对应最终目标报告共61绿色用例，未跑全仓/压测/Bot。09请求、首页交互文档和RESULTS已写；兼容发布、开关切换、真机仍PENDING |

实现分工为 exposure_store、candidates、frontend 三个子 agent，根 agent 负责协议/HTTP/会话编排与集中 Maven，独立 review agent 只读审查。没有自动提交、推送或合并。用户后续要求前端不需要更多测试，已在当前8项专项/typecheck后停止新增与重复运行。无用Mockito桩、错误批量API/fixture导入已按真实失败修正；结果与测试替身边界详见 `../api-test/RESULTS.md` 的本日小节。

关键可视阈值断言在临时副本中故意写错后，单用例出现 ERR_ASSERTION/exit1，原测试保持不变且副本已删除。上线步骤：兼容后端 → 小程序编译/设备验收 → 显式设置 `QUANTA_RECOMMEND_DISCOVERY_ENABLED=true` 并重启 → 09真实JWT/游客验收；关闭开关回退旧能力，不声称旧匿名推荐具备新曝光去重。

实际验证命令按变更增量分批执行，目标为：

```powershell
mvn '-Dtest=RecommendDiscoveryPropertiesTest,RecommendProtocolTest,RecommendSessionServiceImplTest,RecommendDiscoverySelectorTest,RecommendRerankServiceRankCandidatesTest,ContentServiceImplRecommendSceneTest,RecommendRerankServiceImplTest,SecurityFilterChainTests,RecommendDiscoveryRedisIntegrationTests' test -q
mvn '-Dtest=RecommendRerankServiceRankCandidatesTest,PackageArchitectureTest' test -q
mvn '-Dtest=RecommendDiscoverySelectorTest,RecommendDiscoveryRedisIntegrationTests,RecommendSessionServiceImplTest' test -q
mvn '-Dtest=RecommendDiscoveryHttpIntegrationTests,SecurityFilterChainTests' test -q
# 小程序目录
node --test tests/recommend-discovery.test.cjs tests/bot-comment.test.cjs
node --test tests/recommend-discovery.test.cjs
npm run typecheck
```

实际 MySQL/Redis 测试使用隔离容器，未改变本地/生产数据库。HTTP fixture 对作者/互动/纯排序使用替身，直接设置 BaseContext 的用例只证明可信主体选择；完整 TokenAuthenticationService 与真实JWT链没有在新 fixture 中执行，详见RESULTS。公开路径坏/过期Token按现有 OptionalJwtAuthenticationFilter 回退匿名，不是旧计划误写的401。

本次文件清单（便于和既有未提交工作区区分）：

审查追加修正：探索先排除主排序本页前 pageSize 个 ID；新页可见性复查发现删除时补位，重放不补位；同主体换轮先 drain 已收集的全部曝光，等待在途发送并逐批续发后再发新GET。drain 首次失败立即放行并保留有限重试、最多等待30秒，失败/超时允许未成功记录的曝光重新出现；不把网络失败标为已曝光。身份变化隔离旧actor，ensure在异步阶段检查generation/token/actor。对应新增2个后端实际RED、1个多批次Page测试以及顺序断言临时副本人工变异均已运行。

独立审查已最终收口：三项问题全部修复，最后只读复查未发现明确残留 P1/P2；没有继续扩测。代码和自动化验收完成，设备/完整JWT联调/开关灰度及正式发布保持PENDING，不把这些未执行步骤标为通过。

- 后端编排：`feed/service/impl/RecommendSessionServiceImpl.java`、`RecommendSessionStore.java`、`RecommendExposureServiceImpl.java`、`RecommendDiscoverySelector.java`；新增相应 `RecommendSessionService`、`RecommendExposureService` 接口，修改既有 `FeedQueryService`、`RecommendRerankService` 及 Impl。
- 协议：新增 `RecommendVisitor`、`RecommendExposureDTO`、`RecommendPageVO`、`RecommendSessionPage`、`RecommendSessionSnapshot`、`RecommendSessionExpiredException`、`RecommendExposureController`、`RecommendProtocolAdvice`；修改 `RecommendQueryDTO`、`RecommendProperties` 与 `ContentController` 的推荐入口。
- 内容查询和平台：增量修改 `ContentQueryService`/Impl、`ContentMapper.java`/XML；`RedisConstants` 新 v2 前缀；`SecurityConfiguration` 仅曝光精确路径；新增 4 个 `lua/recommend_*.lua`；`application.yml` 与 `.env.example` 新开关配置。
- 小程序：`pages/home/index.ts/wxml/wxss`、`services/content.service.ts`、`types/api.ts/feed.ts`、`utils/recommend-visitor.ts`、`utils/recommend-exposure.ts`、`mock/recommend-pool.ts`、`tests/recommend-discovery.test.cjs`；首页交互文档 `design-system/校友社区/pages/home.md`。未手写生成的 JS 文件。
- 后端新测试：`RecommendDiscoveryPropertiesTest`、`RecommendProtocolTest`、`RecommendSessionServiceImplTest`、`RecommendDiscoverySelectorTest`、`RecommendRerankServiceRankCandidatesTest`、`RecommendDiscoveryRedisIntegrationTests`、`RecommendDiscoveryHttpIntegrationTests`；存量 `SecurityFilterChainTests` 仅加一条新公开路径保护测试，未删改原断言。
- 验收资料：本计划、同日 design、`docs/api-test/cases/09-recommend-discovery.http`、`docs/api-test/RESULTS.md`。其他任务在 `content.service.ts`/Controller/Mapper 等共享文件里的提交幂等和分层修改均保留，不把它们算作本次实现。

- “推荐／热度”，默认推荐。
- 本地随机游客标识，只用于曝光去重，不建立游客兴趣画像。
- 所有用户约20%，每5条最多1条；不足由主推荐补齐。
- 最近7天的可见、同分类、未曝光帖子；尽量覆盖不同作者和标签。
- 进入可视区域才回传，每条曝光独立24小时失效。
- 扩大召回找未看内容，确实耗尽提示暂无新内容，用户主动选择再看已看内容。
- 只改推荐流；热度排行榜另做。每5分钟版本仅作为后续决定。
- 跟随 `demo0/AGENTS.md` 模块边界、构造注入、注释、Result、可选鉴权与真实依赖验收约束；不引入全局框架或泛化中间件。
- 保存位置按 `demo0/AGENTS.md` §6.0 使用现有 `demo0/docs/plans/`，设计与计划相邻。
- 不改全局 `ScrollResult`，防止影响关注流；使用推荐专属 VO，保留原 JSON 字段。
- 业务行为按 TDD 实施；纯文案、计划无需新增代码测试。保护已有正确断言，只更新本次确实改变的曝光/匿名协议测试。
- 工作区有既有未提交修改，尤其 `ContentController`、`FeedQueryServiceImpl`、`ContentMapper`、`content.service.ts`、`types/api.ts`、`RESULTS.md` 等。实施前重新核对 diff，不覆盖其他任务。

## 1. 协议与服务边界

### 1.1 HTTP 增量契约

沿用 `GET /content/recommend`，保留 `latest` 别名；新首页发 `scene=recommend`。

```http
GET /content/recommend?scene=recommend&pageSize=5&feedSessionId=7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0
X-Guest-Id: 9845e25a-4e42-4f71-ab21-964ec8a68fb5

GET /content/recommend?scene=recommend&pageSize=5&feedSessionId=7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0&pageCursor=<opaque-token>
X-Guest-Id: 9845e25a-4e42-4f71-ab21-964ec8a68fb5
```

- `feedSessionId`：客户端每轮生成 UUID v4；首次 GET 无 `pageCursor`。首次网络重试必须复用同一个 ID，避免创建两轮。
- `pageCursor`：服务端随机令牌映射到不可变页号，仅在该会话内有效，客户端不能任意跳页。已成功生成页可重复读取，不推进两次；未来令牌尚未生成时拒绝。
- `revisitOfSessionId`：只有用户点击“再看已看内容”才传入新会话，指向同访客真正 `EXHAUSTED` 的旧会话。验证原会话状态/主体/分类后，允许新轮忽略历史曝光；不清空曝光。
- 访客标识只在推荐服务调用上带，不修改全局请求层。登录请求以服务器认证 `userId` 为准，忽略游客头；身份不可由 body 指定。匿名必须有合法 UUID 游客头。
- 会话绑定访客、分类、pageSize、再看模式。绑定不符业务400；不存在的后续游标、会话过期HTTP/body均409、`msg=推荐会话已过期，请刷新`。现有ContentFailedException只能表达业务400，不能拿它冒充409；新增推荐专属过期异常及限定两个门面的Advice。繁忙沿用RateLimitExceededException及其全局429处理，前端识别业务码与文案，不修改发布幂等错误分类。

推荐专属 `feed/vo/RecommendPageVO.java`：

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RecommendPageVO {
    private List<ContentVO> list;
    private Double minScore;       // 热度分支保持原值；推荐为null
    private Integer offset;        // 热度分支保持原值；推荐为0
    private Boolean hasMore;
    private String feedSessionId;
    private String nextCursor;
    private String recommendationState; // READY / SEARCHING / EXHAUSTED
    private Boolean canRevisit;
}
```

保留 `FeedQueryService.recommend(RecommendQueryDTO)` 旧入口用于现有测试/调用；增加 `recommend(RecommendQueryDTO, RecommendVisitor)`，返回 `RecommendPageVO`。旧请求包装原 ScrollResult 字段；hot 完全复用原查询。新协议以会话字段+开关进入会话服务，不覆盖所有旧请求行为。

曝光新增：

```http
POST /content/recommend/exposures
Content-Type: application/json
X-Guest-Id: 9845e25a-4e42-4f71-ab21-964ec8a68fb5

{"feedSessionId":"7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0","contentIds":[101,102]}
```

响应 `Result<Void>`。一次1～50个正数ID，先去重；每个ID必须属于该主体该会话实际下发的页面，禁止靠传任意ID污染曝光。会话过期拒绝并停止该轮回传；HTTP网络/5xx允许原批重试。时间用服务端，不采信客户端时间。重复回传幂等，既有24小时窗口不被续长。

### 1.2 精确的新增服务接口

```java
public record RecommendVisitor(Long userId, String guestId) {
    public String actorKey() {
        return userId != null ? "u:" + userId : "g:" + guestId;
    }
}
// record放feed/dto，HTTP门面验证后构造，userId来自BaseContext。
public interface RecommendSessionService {
    RecommendSessionPage page(RecommendVisitor visitor, RecommendQueryDTO query);
}
public interface RecommendExposureService {
    Set<Long> findExposed(RecommendVisitor visitor, Collection<Long> ids);
    void record(RecommendVisitor visitor, String feedSessionId, Collection<Long> ids);
}
// 在现有RecommendRerankService新增纯重排接口，保留rerank旧入口：
List<ContentSnapshotVO> rankCandidates(Long userId, List<ContentSnapshotVO> candidates);
// 内容域公开只读入口，用于Redis窗口外补候选：
List<ContentSnapshotVO> getApprovedRecommendCandidates(
    Integer contentType, LocalDateTime upperTime, Long upperId,
    LocalDateTime beforeTime, Long beforeId, int limit);
Long getApprovedRecommendUpperId(Integer contentType, LocalDateTime upperTime);
```

`RecommendSessionPage`使用Lombok Data/Builder，字段为`List<ContentSnapshotVO> contents`、`String feedSessionId/nextCursor/recommendationState`、`Boolean hasMore/canRevisit`。它是feed服务输出快照与元数据的内部VO；`FeedQueryServiceImpl`继续用现有`assembleSnapshotVOs`装配作者及访问者状态，再构造HTTP `RecommendPageVO`。新协议装配时显式传`visitor.userId()`，旧装配入口委托现有BaseContext值；会话服务不能反向注入FeedQueryService，避免依赖环或重复装配实现。

`rankCandidates` 沿用现有 `scoreAndSort` 的 α、匹配与显式项，不读写曝光；游客不调用画像服务。探索按同一词表解标签，优先非负显式偏好，避免另建标签/画像模型。

### 1.3 Redis 状态与原子性

统一新前缀 `recommend:v2:`，不落入 `user:profile:`；旧 `recommend:exposed:{userId}` 不改类型，不迁移为 ZSET。旧返回即曝光不能证明真实看见，新协议不继承它。

- `recommend:v2:exposed:{actor}`：ZSET，member=contentId，score=失效毫秒。读前清理 `score<=now`；写先清过期，再 `ZADD NX now+24h`，整体 TTL 用最后一次有效写入后的25小时清理空闲主体，不能替代逐条失效。
- `recommend:v2:session:{actor}:{sessionId}`：会话元数据、起始时间、模式、召回位置、随机种子、已下发ID、待选队列、游标映射与不可变页面ID。
- 每次成功访问更新闲置TTL，但不能越过创建后的2小时。重复请求只读取原页ID，重新查询可见性并装配作者/访问者状态，不缓存用户化VO。
- 同一会话构页串行：`SET NX PX` 带随机owner；提交页面与推进游标使用Lua核对owner，会话完整提交后才回响应；释放也compare-and-delete。不得复用当前可误删的定时任务锁。
- 抢锁失败最多短暂读已提交页，仍未完成返回429可重试；原子提交前进程崩溃不留下已推进游标。锁过期旧owner不得提交。
- 一个访客不同会话可以同时选中同帖；真实曝光是统一记录，后续构页过滤。此轮只保证会话内下发不重复，不宣称跨设备/跨会话强独占。
- 限额按主体最多20活跃会话；仅清理已过期索引，不删除合法活跃轮。输入长度与批次数限定，日志不输出原始访客标识和Token。

## 2. 任务与文件

以下路径相对仓库根。实际新文件按下列职责建立，不创建空层。

### Task 1：推荐协议、访客与兼容分支

**Files**

- Modify: `demo0/src/main/java/com/quanta/demo0/feed/dto/RecommendQueryDTO.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/feed/service/FeedQueryService.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/feed/service/impl/FeedQueryServiceImpl.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/content/controller/user/ContentController.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/feed/properties/RecommendProperties.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/dto/RecommendVisitor.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/dto/RecommendExposureDTO.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/vo/RecommendPageVO.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/exception/RecommendSessionExpiredException.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/controller/user/RecommendProtocolAdvice.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/controller/user/RecommendExposureController.java`
- Test: `demo0/src/test/java/com/quanta/demo0/feed/service/impl/ContentServiceImplRecommendSceneTest.java`
- Test: `demo0/src/test/java/com/quanta/demo0/feed/controller/user/RecommendProtocolTest.java`（新增）

**Interfaces:** 消费原 `recommend(query)`；产出 §1.1/1.2 的DTO/VO和HTTP门面。Task 2/3提供新分支服务后接通，新分支在未接通之前保持关闭，不能用假的成功响应过测试。

- [ ] RED：给现有场景测试加 hot 不受推荐曝光影响、`latest/recommend` 别名、无新字段旧调用JSON兼容断言；给HTTP测试加游客头、认证身份优先、非法UUID/大小/分类/曝光批次验证。

```java
assertThat(json.path("data").has("list")).isTrue();
assertThat(json.path("data").get("minScore").asDouble()).isEqualTo(expectedHotScore);
assertThat(json.path("data").get("offset").asInt()).isEqualTo(expectedOffset);
// 认证userId=12，即使附带另一个游客头，session必须属于u:12。
verify(sessionService).page(eq(new RecommendVisitor(12L, null)), any());
```

- [ ] 运行 `mvn '-Dtest=ContentServiceImplRecommendSceneTest,RecommendProtocolTest' test`，在demo0目录确认目标新增行为RED，不以无关环境异常代替。
- [ ] GREEN：新增字段 `feedSessionId/pageCursor/revisitOfSessionId`，配置 `quanta.recommend.discovery.enabled=false` 及配置表默认值；新增VO只包装推荐响应。控制器只做校验、取可信身份、调用服务；游客UUID不是鉴权凭证。
- [ ] 过期异常继承RuntimeException并固定文案；Advice使用`@RestControllerAdvice(assignableTypes={ContentController.class, RecommendExposureController.class})`，只捕获该专属异常，返回`ResponseEntity.status(409).body(Result.error(409, exception.getMessage()))`。不接管旧接口的ContentFailedException或修改全局业务400语义。
- [ ] 移除“游客必然等同热度流”旧注释误导，明确“推荐热度兜底排序＋探索”，保留 hot 实现。
- [ ] 重跑上述测试，通过后审查兼容diff；可独立提交 `feat(feed): add discovery protocol and visitor boundary`，只stage本任务变更。

### Task 2：真实曝光、会话存储和并发重试

**Files**

- Create: `demo0/src/main/java/com/quanta/demo0/feed/service/RecommendSessionService.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/service/RecommendExposureService.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/service/impl/RecommendSessionStore.java`（状态存取和owner锁）
- Create: `demo0/src/main/java/com/quanta/demo0/feed/service/impl/RecommendExposureServiceImpl.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/vo/RecommendSessionSnapshot.java`（内部状态，不暴露HTTP）
- Modify: `demo0/src/main/java/com/quanta/demo0/platform/redis/constant/RedisConstants.java`
- Create: `demo0/src/main/resources/lua/recommend_exposure_record.lua`
- Create: `demo0/src/main/resources/lua/recommend_session_commit.lua`
- Create: `demo0/src/main/resources/lua/recommend_session_unlock.lua`
- Test: `demo0/src/test/java/com/quanta/demo0/feed/service/impl/RecommendDiscoveryRedisIntegrationTests.java`（新增）

**Interfaces:** `RecommendSessionStore` 必须暴露 `load(visitor, sessionId)`、`create(visitor, sessionId, query)`、`loadPage(visitor, sessionId, cursor)`、`tryLock(visitor, sessionId, owner)`、`commitPage(visitor, sessionId, owner, RecommendSessionSnapshot nextState)`、`unlock(visitor, sessionId, owner)`；nextState包含页ID、nextCursor、delivered集合、pending队列、源游标、扫描是否结束、累计输出数量及创建/访问时间，原子提交整个下一状态，不只提交页号。页游标与结构按§1固定。Exposure.record通过store验证下发归属。

- [ ] RED：真实Redis测试两个访客隔离、同条重传不续期、不同条失效时间不同、未下发ID不能曝光、不同owner释放/提交失败、并发同页只有一份页面、TTL边界。

```java
// fixture使用Testcontainers redis:7.2-alpine和现有LettuceConnectionFactory模式；
// 建会话并提交[101,102]后再回传，不能只mock存储。
exposure.record(visitor, sessionId, List.of(101L));
Double first = redis.opsForZSet().score(exposedKey, "101");
exposure.record(visitor, sessionId, List.of(101L));
assertThat(redis.opsForZSet().score(exposedKey, "101")).isEqualTo(first);
redis.opsForZSet().add(exposedKey, "101", System.currentTimeMillis() - 1);
assertThat(exposure.findExposed(visitor, List.of(101L))).isEmpty();
assertThatThrownBy(() -> exposure.record(visitor, sessionId, List.of(999L)))
    .isInstanceOf(ContentFailedException.class);
```

- [ ] 执行 `docker info` 确认环境，再运行 `mvn '-Dtest=RecommendDiscoveryRedisIntegrationTests' test`，捕捉可解释RED。
- [ ] GREEN：Lua先清过期再NX写入；校验owner后一次提交页ID、delivered集合、召回状态、令牌与TTL。曝光记录不触发画像事件。
- [ ] 暂停旧owner至租约失效，第二owner构页，恢复旧owner提交应失败；用同一cursor再次读应返回原页ID。
- [ ] 重跑真实Redis测试；可提交 `feat(feed): persist discovery sessions and visible exposures`。

### Task 3：探索配额、扩召回与完整耗尽判定

**Files**

- Modify: `demo0/src/main/java/com/quanta/demo0/feed/service/RecommendRerankService.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/feed/service/impl/RecommendRerankServiceImpl.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/service/impl/RecommendSessionServiceImpl.java`
- Create: `demo0/src/main/java/com/quanta/demo0/feed/service/impl/RecommendDiscoverySelector.java`（配额与确定性抽样）
- Create: `demo0/src/main/java/com/quanta/demo0/feed/vo/RecommendSessionPage.java`（快照及页面元数据）
- Modify: `demo0/src/main/java/com/quanta/demo0/content/service/ContentQueryService.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/content/service/impl/ContentQueryServiceImpl.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/content/mapper/ContentMapper.java`
- Modify: `demo0/src/main/resources/mapper/content/ContentMapper.xml`
- Test: `demo0/src/test/java/com/quanta/demo0/feed/service/impl/RecommendDiscoverySelectorTest.java`（新增）
- Test: `demo0/src/test/java/com/quanta/demo0/feed/service/impl/RecommendSessionServiceImplTest.java`（新增）
- Test: `demo0/src/test/java/com/quanta/demo0/content/service/impl/RecommendCandidateQueryIntegrationTests.java`（新增，沿用已有MySQL容器依赖）
- Test: 现有 `RecommendRerankServiceImplTest.java`、`ExplicitPreferenceRerankTest.java`、`RecommendRerankRedisIntegrationTests.java`

**Interfaces:** 消费Task 1 DTO、Task 2存储/曝光；产出`page`与`rankCandidates`，接通Task 1新协议。内容Mapper只返回内容entity，ContentQueryService转快照，不泄漏跨域。

- [ ] RED：固定随机种子验证每5条最多1探索、跨小页累计、无探索补齐、hot/latest交集不重复、显式厌恶不被探索提拔、无标签仍可按作者多样化、7天边界、游客不读画像。
- [ ] RED：存量窗口全部已曝光但第301～600位存在未看帖，仍返回内容；超过预算返回SEARCHING；真实DB源结束才EXHAUSTED；revisit必须指向本主体耗尽轮。

```java
// Selector测试固定候选及seed，不断言某一次随机运行“恰好”有某帖。
assertThat(pageIds).doesNotHaveDuplicates();
assertThat(explorationIds).hasSize(1);
assertThat(explorationIds).allMatch(id -> recentUnexposedIds.contains(id));
assertThat(expandedPage.getRecommendationState()).isEqualTo("READY");
assertThat(budgetPage.getRecommendationState()).isEqualTo("SEARCHING");
assertThat(budgetPage.getHasMore()).isTrue();
assertThat(budgetPage.getCanRevisit()).isFalse();
```

- [ ] 运行 `mvn '-Dtest=RecommendDiscoverySelectorTest,RecommendSessionServiceImplTest,RecommendCandidateQueryIntegrationTests' test` 确认RED。
- [ ] GREEN：先按原分数排序主候选；按累计输出位置算探索配额，在会话种子下从近期合格候选抽取，排除当页主推荐与已下发ID，选中后从主队列删除。优先不同作者/标签，无法满足允许回退，不过滤合法空标签。
- [ ] GREEN：会话固定上界`startedAt/upperId`，增量扩ZSET窗口并去重。窗口上限后MySQL键集补召回，始终先滤真实曝光和会话下发，再重排候选。排名稳定限于已生成页面；追加窗口不插到历史页前。会话服务返回RecommendSessionPage，FeedQueryServiceImpl装配RecommendPageVO，不复制原作者/互动状态映射、不形成service依赖环。

```sql
-- 新selectApprovedRecommendCandidates核心；动态SQL只在参数非空时追加游标条件。
WHERE c.is_deleted = 0 AND c.audit_status = 1
  AND c.content_id <= #{upperId}
  AND c.create_time <= #{upperTime}
  AND (c.create_time < #{beforeTime}
       OR (c.create_time = #{beforeTime} AND c.content_id < #{beforeId}))
-- contentType非空追加 c.content_type = #{contentType}
ORDER BY c.create_time DESC, c.content_id DESC
LIMIT #{limit}
```

- [ ] SQL首批不带before条件；`getApprovedRecommendUpperId`调用同内容域Mapper新增`selectApprovedRecommendUpperId(contentType,upperTime)`，SQL为`SELECT COALESCE(MAX(content_id),0) FROM tb_content WHERE is_deleted=0 AND audit_status=1 AND create_time<=#{upperTime}`，分类非空追加content_type条件。该上界固定于会话；首批与后续都加`content_id<=upperId`，排除之后新入库的帖。
- [ ] 单次扫描预算耗尽保存续扫位置，生成可重放SEARCHING页与下一游标；EXHAUSTED时才`hasMore=false, canRevisit=true`（确有可见历史时）。没有任何可见内容则canRevisit=false。
- [ ] 出页前使用`getContentSnapshots`再次过滤可见性，缺失时继续补候选；重放页面再次校验，不为补齐旧页移动其后页边界。长度不足不能直接判耗尽。
- [ ] 旧测试中“匿名不写曝光”的断言保留用于旧分支；新协议测试验证GET不写真实曝光、POST才写。旧返回即写SET依旧只在旧分支，不以删除测试掩盖兼容。
- [ ] 新SQL实库验证同一时间多ID、分类、删除、待审与键集边界；执行EXPLAIN核对现有索引。没有证据不新增索引；若必要，记录查询证据并另列可回滚索引SQL，不默认全表迁移。
- [ ] 重跑上述测试，加 `mvn '-Dtest=RecommendRerankServiceImplTest,ExplicitPreferenceRerankTest,RecommendRerankRedisIntegrationTests,PackageArchitectureTest' test`；可提交 `feat(feed): add exploration and bounded recall continuation`。

### Task 4：曝光HTTP可选鉴权与错误边界

**Files**

- Modify: Task 1的 `RecommendExposureController.java`、`ContentController.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/platform/security/config/SecurityConfiguration.java`
- Read: `demo0/src/main/java/com/quanta/demo0/platform/security/filter/OptionalJwtAuthenticationFilter.java`
- Test: `demo0/src/test/java/com/quanta/demo0/feed/controller/user/RecommendProtocolTest.java`
- Test: `demo0/src/test/java/com/quanta/demo0/platform/security/SecurityFilterChainTests.java`
- Create: `demo0/src/test/java/com/quanta/demo0/reliability/RecommendDiscoveryHttpIntegrationTests.java`

**Interfaces:** `POST /content/recommend/exposures`仅新增这一条可选鉴权路径，不把`/content/**`整体放行；默认过滤器对有效/无效JWT的处理保持一致。

- [ ] RED：无Token+合法游客可读/回传；有效Token只写本人曝光；伪造userId无效；盗用其他会话返回统一错误；坏/过期Token在公开路径按现有 OptionalJwtAuthenticationFilter 回退匿名，并由合法游客头识别主体；私有路径仍401；过期会话409、并发繁忙/会话额度429、Redis错误非空成功。
- [ ] 运行 `mvn '-Dtest=RecommendProtocolTest,SecurityFilterChainTests,RecommendDiscoveryHttpIntegrationTests' test`，真实鉴权/Redis/MySQL测试需要现有容器环境，不mock安全过滤器。
- [ ] GREEN：精确开放曝光路径并复用Result/异常；所有service入参显式传visitor，不依赖后台线程BaseContext。批次上限和会话归属校验不能只放客户端。
- [ ] 本轮不扩展全局`@RateLimit`（当前只支持登录用户）。会话数量、pageSize、批次、构页锁与扫描预算是新协议资源边界；公网部署前若需要IP限流走现有入口/代理治理，不能声称随机游客ID能防攻击。
- [ ] 在真实HTTP中完成 GET两页→曝光POST→新会话排除已曝光→同一cursor重试→删除测试内容后重放不展示，断言返回ID及Redis记录，不只看200。
- [ ] 重跑上述安全/HTTP测试；可提交 `feat(feed): expose optional-auth discovery feedback endpoint`。

### Task 5：小程序稳定会话与可视曝光

**Files**

- Modify: `demo0-miniprogram/miniprogram/pages/home/index.ts`
- Modify: `demo0-miniprogram/miniprogram/pages/home/index.wxml`
- Modify: `demo0-miniprogram/miniprogram/pages/home/index.wxss`（仅状态/入口样式）
- Modify: `demo0-miniprogram/miniprogram/services/content.service.ts`（保留当前发布幂等改动）
- Modify: `demo0-miniprogram/miniprogram/types/feed.ts`
- Modify: `demo0-miniprogram/miniprogram/types/api.ts`（推荐专属类型，不改关注响应）
- Create: `demo0-miniprogram/miniprogram/utils/recommend-visitor.ts`
- Create: `demo0-miniprogram/miniprogram/utils/recommend-exposure.ts`
- Modify: `demo0-miniprogram/miniprogram/mock/recommend-pool.ts`（新场景别名与新字段，只用于开发展示）
- Read: `demo0-miniprogram/miniprogram/utils/submission.ts` 的导出 `createSubmissionToken`，复用安全UUID生成函数，不触碰发布凭证存储逻辑。
- Read: `demo0-miniprogram/miniprogram/utils/feed-timer.ts`、`utils/pagination.ts`、`tests/production-compiler.cjs`
- Create: `demo0-miniprogram/tests/recommend-discovery.test.cjs`

**Interfaces:** `getRecommendFeed`扩展返回`feedSessionId/nextCursor/recommendationState/canRevisit`；新增`reportRecommendExposures({feedSessionId,contentIds})`。utils生成/读取guestId、观察事件批队列；身份+会话作为队列key，旧请求不能混入新轮。

- [ ] RED：用production-compiler编译真实TS，再stub wx验证游客ID持久化、首请求重试同sessionId、翻页同cursor重试、旧请求不覆盖新轮、SEARCHING不误判finished、hot请求不携带曝光上下文。

```javascript
const test = require('node:test');
const assert = require('node:assert/strict');
const { compileProduction } = require('./production-compiler.cjs');
const build = compileProduction('miniprogram/utils/recommend-exposure.ts');
test.after(() => build.cleanup());
// fixture用wx观察器回调驱动真实生产队列，不读取源码字符串当行为测试。
test('invisible cards do not produce exposure reports', async () => {
  const batches = [];
  const queue = build.entry.createExposureQueue(async batch => { batches.push(batch); });
  queue.observe({ sessionId: 's1', actorKey: 'g1', contentId: 101, ratio: 0 });
  await queue.flush();
  assert.equal(batches.length, 0);
  queue.observe({ sessionId: 's1', actorKey: 'g1', contentId: 101, ratio: 0.6 });
  await queue.flush();
  assert.deepEqual(batches[0].contentIds, [101]);
});
```

实现接口`createExposureQueue(send)`返回`observe({sessionId,actorKey,contentId,ratio})/flush()/dispose()`。send批次只含`feedSessionId/contentIds`，actorKey用于客户端分组，不能作为服务器用户身份。

- [ ] 运行 `node --test tests/recommend-discovery.test.cjs`，确认RED；新增测试不修改存量bot-comment断言。
- [ ] GREEN：首页标签改“推荐”，新类型支持`recommend/latest/hot`。每轮安全UUID；已有工具随机源不可用时明确显示加载失败并允许重试，不生成空/固定ID共享曝光。游客ID本地保存；读写失败保持当前内存ID，说明退出后可能重置，不影响当轮分页。
- [ ] GREEN：请求只对推荐带游客头；保留现有getRecommendFeed错误结构与发布路径。没有新响应字段时回退旧模式，不伪称具有探索/真实曝光。
- [ ] GREEN：WXML给卡片外包装 `.home__observed-card` 与contentId；Observer用`observeAll:true`观察包装节点，50%阈值，扣除实际吸顶栏高度。新列表setData后刷新观察器，旧观察器dispose。
- [ ] 回传2秒批一次、达到20条立即发（服务器上限50）；成功再确认本地已报集合。失败保留原批次，1/2/4秒退避重试，最多3次后留在内存待下一次可见/恢复触发；最多500待报ID，超限按批立即尝试发送，不无限缓存。禁止后台无限重试。
- [ ] onHide先停止接收观察回调并触发一次已有队列flush；onUnload清理观察器/计时器，回传失败不宣称成功。身份改变时不把旧guest批次带新JWT发送，也不把账号批次匿名发送。
- [ ] GET加载失败保留同sessionId/cursor，重试原页；响应令牌推进只在本页被采纳后进行。loadMore也校验homeFeedRequestToken，不能只在首屏校验。
- [ ] 推荐停用45秒静默替换；hot保持原行为。onShow比较前次身份，变化时重置推荐；否则继续旧会话。过期409展示“推荐已更新，刷新继续”，不自动反复重开。
- [ ] SEARCHING展示“正在寻找更多内容”，允许沿nextCursor继续（自动最多2次，然后“继续查找”）；EXHAUSTED展示“暂时没有新内容”和可用的“再看已看内容”。再看新轮带revisitOfSessionId，不清历史曝光。彻底无帖子仍保留“去发布”。
- [ ] `resolveFinished`在推荐新协议由state/hasMore决定，不以本页少于5条或为空推断全站耗尽；关注/hot继续沿用旧函数。
- [ ] 运行 `npm run typecheck` 与 `node --test tests/recommend-discovery.test.cjs tests/bot-comment.test.cjs`；生成JS遵循当前小程序构建流程，不手写TS与JS两套实现。开发者工具实际编译后确认运行到新TS产物。
- [ ] 可提交 `feat(miniprogram): add discovery sessions and viewport exposure reporting`，只stage本任务必要文件。

### Task 6：联调、兼容发布与文档验收

**Files**

- Create: `demo0/docs/api-test/cases/09-recommend-discovery.http`
- Modify: `demo0/docs/api-test/TEST_PLAN.md`、`demo0/docs/api-test/RESULTS.md`（只在实际执行后）
- Modify: `demo0/src/main/resources/application.yml`（discovery开关与参数）
- Modify: `demo0-miniprogram/design-system/校友社区/pages/home.md`（双流与状态说明）
- Modify: 本计划的任务状态和实际验证证据；设计变更先回写相邻设计。

**Interfaces:** 交付Task 1～5完整链路，旧latest/hot/关注协议仍可访问；不得把后续五分钟热榜称为本轮完成。

- [ ] 开启本地discovery配置，确认MySQL/Redis/主服务真实状态，沿用真实登录流程；新协议读链不要求启动Bot、不调用模型。
- [ ] 游客A连取3页，滚动只看前两张，验证Redis仅两条曝光；刷新新轮不返回这两条，未进入屏幕的帖子可以再次出现。游客B不受A曝光影响。
- [ ] 同cursor丢弃首次响应再重试，ID一致；确认网络失败不会消耗下一页。构页期间删除测试帖，新页/重放都不泄漏它。
- [ ] 登录用户分别验证无画像热度兜底、有画像排序差异、显式偏好贡献、探索配额、退出/登录旧异步响应不会串轮。
- [ ] 造测试候选覆盖超过150窗口、SEARCHING续扫、真正耗尽、主动再看；只用可追踪测试账号和可清理测试内容，不批量改生产曝光/画像。
- [ ] 同一分类两位访客热度返回相同排名；POST推荐曝光后hot仍可出现该帖。本轮不要求解决原实时热度游标的相邻排序问题，记录后续入口。
- [ ] 开发者工具验证IntersectionObserver与吸顶遮挡、下拉刷新、翻页失败、切分类、前后台；再执行一次真机验证。未执行的设备项标PENDING，环境失败标BLOCKED。
- [ ] 关闭discovery开关，确认新前端可回退旧响应；后端旧接口可继续服务旧客户端。旧协议的匿名重复问题属于旧能力，不误写成新协议已通过。
- [ ] 运行必要定向测试、类型检查与PackageArchitectureTest；没有新的失败/变更不重复全量长测。不修改pom/CI/.gitignore降低门禁。
- [ ] 公开契约/安全/Redis并发变更由未参与实现者独立审查；按AGENTS要求，关键新增测试在临时副本做一次错误断言变异抽查并恢复。当前计划阶段无需代理审查。
- [ ] RESULTS只写真实证据。阶段性提交`docs(feed): record discovery acceptance and rollout`，不自动push/merge。

## 3. 配置、迁移与回滚

推荐新增配置集中在`RecommendProperties.Discovery`及`quanta.recommend.discovery`：

| 字段 | 默认值 |
|---|---|
| enabled | false，联调后显式开启 |
| explorationRatio | 0.2 |
| recentDays | 7 |
| exposureHours | 24 |
| sessionIdleMinutes / sessionMaxMinutes | 30 / 120 |
| maxActiveSessions / maxPageSize / maxExposureBatch | 20 / 20 / 50 |
| recallWindowSteps | [150,300,600,1200]（每池） |
| scanBudget / candidateBatchSize | 3000 / 150 |

比例[0,1]、时间/条数正数、窗口严格递增、idle不大于max；配置校验走现有Bean Validation。无MySQL表/行为迁移、无MQ变更；新键与旧曝光隔离。

关闭开关或回滚小程序可回到旧流程，新Redis键自然过期，无需删除旧键。会话存储中断明确让客户端刷新，不恢复半页。内容只读口回滚不影响已存在数据。没有用户授权不执行生产迁移、批量清理、推送或发布。

## 4. 自查与覆盖清单

| 用户决定/关键风险 | 对应任务 |
|---|---|
| 推荐／热度入口 | Task 1、5 |
| 游客标识，仅曝光 | Task 1、2、5 |
| 所有人约20%探索与7天 | Task 3 |
| 可视曝光、逐条24小时 | Task 2、4、5 |
| 窗口外召回、真正耗尽/再看 | Task 3、5、6 |
| 首页/翻页重试、并发租约 | Task 2、3、5、6 |
| 旧接口/可选JWT兼容 | Task 1、4、6 |
| 静默刷新与身份切换 | Task 5、6 |
| 删除/审核最新事实 | Task 3、4、6 |
| 本轮不改热榜、五分钟另做 | Spec §1/5，Task 6 |

实施按Task 1→2→3→4→5→6顺序推进。Task 1新契约在Task 3/4接通前不开启；每阶段保留定向测试证据，不把中间脚手架称为已可用。本文件复核了字段/接口名、相对路径与八项决策覆盖；本次仅完成设计和计划，所有实施复选框保持未勾选。
