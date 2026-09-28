# 推荐主题标签与 Agent 显式偏好 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本会话没有这两个子技能，使用已提供的协作工具执行同等的分工、红绿验证及独立审查；不另开多份计划。

**Goal:** 完成总方案第 3 项的③主题标签增量链路和④未来显式偏好同步，保留存量回填的独立执行边界。

**Architecture:** demo0 是受控词表、内容标签及推荐画像的权威边界，异步副作用沿用 MySQL Outbox → RabbitMQ → Inbox。QuantaBot 将未来记忆变更的待同步标记随 Qdrant 单点写入持久化，后台映射明确偏好并经 BOT HTTP 提交；HTTP 故障不阻塞回复，重试不重复加权。推荐读取只访问主服务画像，不依赖 Agent 在线。

**Tech Stack:** 复用 pom.xml、uv.lock 中既有 Spring AI、MyBatis、MySQL、RabbitMQ、Redis、Qdrant、httpx 与 pytest；不升级依赖、不引入框架。

**Spec:** `demo0/docs/后续demo0优化总方案.md` 第 3 项；本会话 grill-me 已确认的下述需求结论优先于旧文档中仍写着待立项的排期。

## 需求结论与 Global Constraints

- ③每帖可有多个主题标签，最多 3 个，按相关性取最相关者，不强凑两三个；来自已确认的下述 26 项词表。结构化 contentType 标签保留，不计入主题标签上限；唯一扩展入口为 `UserProfileServiceImpl.resolveContentTags`。
- 无主题命中的内容允许返回空数组并标记为已处理，不编造标签、不让合法空结果无限重试。未知 ID、超限、非法 JSON 属无效模型输出，拒绝并进入重试；重复 ID 去重。
- ③仅给当前审核通过且未删除内容打标。先完成增量链路、可限量/可续跑工具与小批真实验证，工具跑通后的全量存量回填是一次独立批量执行，不在本次偷偷执行。
- ④仅同步未来发生的明确主题喜好/厌恶，兼容 user、feedback 记忆。学院、年级等个人事实不当作兴趣；不回填历史记忆或行为。
- ④未来 ADD、UPDATE、DELETE 均支持；替换/撤销旧贡献，重复投递不重复加权，乱序旧事件不能复活已撤销的偏好。负偏好强降权、不硬屏蔽。
- 偏好采用受控词表别名和明确偏好表达的保守映射，无法判断就不贡献兴趣，不修改 M5 决策提示词，不新增偏好提取 LLM 调用。
- MySQL 事实与 Outbox 同事务；Inbox 手动 ACK 在处理成功之后。Redis 使用当前事实的覆盖式快照，不以重复累加模拟幂等。
- HTTP 只允许配置的 BOT 服务身份（词表/信号）或现有运营权限（回填），验证目标用户有效性；不得让 Agent 直连主库。事件只传主题与标识，不传记忆原文。
- 保持匿名推荐、hot 纯热度、审核链路及现有前端契约；不修改小程序、管理端、M5 冻结证据。
- 只做必要定向测试、一次受影响边界的真实链路验证与一次独立审查；不跑 Persona/红队或扩大评测。无真实证据的项保持未验证，不称全量回填已完成。
- 工作区：`C:/Users/dwc12/.codex/worktrees/recommend-topics-preferences/QuantaCommunity`，分支 `codex/recommend-topics-preferences`，基线 `f023c770701c2dff45d9bf02c3e544b993b20726`。不覆盖原工作区未提交修改，不自动 push/merge。

## 契约与迁移边界

### 已确认的完整词表（26 项）

| ID | 标签 | 归类边界 |
|---|---|---|
| course_study | 课程学业 | 课程、作业、期末考试 |
| software_technology | 软件技术 | 编程、开发工具、AI 应用，不另设同层人工智能标签 |
| experience_sharing | 经验分享 | 学习、工作或实习经验；可与领域标签共存，替换电子硬件 |
| research_project | 科研项目 | 实验、论文、科研方法；竞赛按实际领域归类，不设泛竞赛标签 |
| further_education | 升学规划 | 考研、保研、留学 |
| career_internship | 求职实习 | 简历、面试、招聘、实习 |
| campus_policy | 校务政策 | 校园规则及政策解读 |
| campus_services | 校园办事 | 校园办事流程、材料、服务 |
| housing | 宿舍住宿 | 宿舍、租住及住宿事项 |
| dining | 食堂餐饮 | 食堂、餐饮及吃饭讨论 |
| transport | 出行交通 | 公交、通勤及出行 |
| saving | 消费省钱 | 消费选择、优惠及省钱 |
| second_hand | 二手闲置 | 二手交易及闲置物品 |
| basketball | 篮球 | 篮球运动 |
| football | 足球 | 足球运动 |
| badminton | 羽毛球 | 羽毛球运动 |
| table_tennis | 乒乓球 | 乒乓球运动 |
| running | 跑步 | 跑步运动 |
| swimming | 游泳 | 游泳运动 |
| strength_training | 力量训练 | 器械、抗阻及力量训练；不设笼统运动标签 |
| gaming | 游戏娱乐 | 游戏及相关娱乐 |
| music_arts | 音乐文艺 | 音乐及文艺活动 |
| reading_film | 阅读影视 | 阅读、影视作品讨论 |
| club_activity | 社团活动 | 社团、组织及社团活动 |
| relationships | 人际交往 | 人际沟通及交往 |
| psychological_adjustment | 心理调适 | 压力应对及心理调适内容；不根据个人情绪事实推断兴趣 |

经验分享是内容性质而非互斥分类：学习经验可为 `experience_sharing + course_study`，实习经验可为 `experience_sharing + career_internship`。偏好别名不得把窄范围厌恶扩成整类厌恶；运动七项独立映射，不再映射到笼统“运动健身”。

主服务词表接口 `GET /bot/profile/topics` 返回 Result 包裹的主题数组，每项 `{id,label,aliases}`，20–50 项且 id 唯一；主服务资源文件是唯一词表来源。

主服务 `POST /bot/profile/events` 接收以下快照，成功表示 MySQL 与 Outbox 已持久化，不等同 Redis 已消费：

```json
{"eventId":"b09ac1bd-f5f3-43e0-a93b-2f2606daf0a8","userId":123,"memoryId":"6110e1d664174ad8a67a73cb49b28edc","personaVersion":"v1","revision":1790550000000000,"operation":"UPSERT","topics":["basketball"],"valence":"positive"}
```

`revision` 是该记忆变更的 UTC epoch 微秒 long；同次重试保留 eventId/revision。DELETE 携带相同 memoryId、更新的 revision、空 topics 与空 valence。UPDATE 对旧点产生 DELETE、对新点产生 UPSERT。无关记忆不产生 UPSERT。HTTP 幂等键 eventId，内容不一致的复用拒绝。可靠事件类型 `user.profile.updated`，仅携带 userId 等校准标识；消费者读取主服务当前事实而非信任旧消息增量。

新增 nullable `tb_content.tags JSON`，原应用可以忽略它；新增仅含主题偏好与事件标识的主服务事实表，迁移脚本放 `demo0/src/main/resources/db/V_recommend_topics_profile.sql`。先迁移再部署主服务，再开启 Agent 同步；回滚先关新增同步/打标开关，再回滚代码，暂留新列/表避免丢失证据。显式 Hash 与行为 Hash 分离，避免负数进入行为分母或被行为衰减任务误删；推荐读侧在同一算分入口组合二者。

### 部署、回填与回滚入口

1. 在目标 MySQL 数据库执行 `src/main/resources/db/V_recommend_topics_profile.sql` 后部署主服务。Agent 后部署/开启 `QUANTABOT_PROFILE_SYNC_ENABLED`；主服务可先用 `QUANTA_TOPIC_TAGS_ENABLED=false` 暂停新增打标消费，队列保留，开关改动需重启生效。
2. 核对运营账号具备 `CONTENT_AUDIT` 权限，将 token 放本地进程环境 `DEMO0_ADMIN_TOKEN`（不入库）。小批命令：`python demo0/scripts/backfill_content_topics.py --base-url http://127.0.0.1:9191 --after-id 0 --limit 3 --max-batches 1`。输出 `nextAfterId` 是“已登记 Outbox”的游标，不是模型成功数；中断后以最后输出游标继续，必须另查 Inbox/DLQ 和剩余 `tags IS NULL`。
3. 全量存量回填作为后续独立一次批量执行；DLQ 尚未解决时不能仅凭游标宣称全量成功。主题任务按词表初版/帖子 ID 形成稳定 eventId，并在 Outbox 唯一键原子去重；游标重跑不会新建付费任务。模型调用失败本身仍可能产生费用，不能宣称外部付费 API 的 exactly-once。DEAD 使用原任务的人工重放，不靠新建任务绕过失败。
4. 回滚先停 Agent 新同步、停打标消费者，再回滚应用；保留 tags 列与事实表，避免丢事件/撤销证据。恢复消费与现有 Outbox/Inbox DLQ 重放沿用 `docs/outbox-plan.md`，不另造状态机。专用主队列带 broker DLX，无法解析/缺身份的毒消息保留原消息到死信而不永久 requeue；新队列尚未部署，禁止对已有同名旧参数队列直接做不兼容声明。

## Task 1：③主题标签、增量链路与可续跑工具

**Files:** `entity/Content.java`、`mapper/ContentMapper.java`、`resources/mapper/ContentMapper.xml`；新增受控词表资源与 `service/TopicCatalog.java`、`service/ContentTopicTagService.java`、`service/Impl/ContentTopicTagServiceImpl.java`、专用 MQ 配置/消息/消费者/辅助生产者；修改实际审核通过入口；新增运营回填入口及 `demo0/scripts/backfill_content_topics.py`。主代理统一接入 Outbox/Inbox 共享文件、配置和 `resolveContentTags`，不由子代理同时编辑。

**Interfaces:** `TopicCatalog.topics()` 返回词表；`TopicCatalog.parseStoredTags(String)` 返回去重后的有效 ID；`ContentTopicTagService.tagContent(Long contentId)` 重新读取当前可见内容、调现有 ChatModel 并条件写标签；`enqueueBackfill(long afterId,int limit)` 分页入队并返回下次游标，正文不进新增事件。重复已有标签可跳过；审核/删除状态变化时写入条件不满足，不能覆盖非法内容。

- [x] RED：新增定向测试先失败；初始新增类型缺失为编译 RED（不冒称完整行为 RED），后续非法/超限、可见性、已有标签与回填边界已验证。

```java
assertThat(TopicCatalog.parseStoredTags("[\"basketball\",\"unknown\",\"basketball\"]"))
        .containsExactly("basketball");
```

- [x] GREEN：最小实现词表白名单校验、JSON 标签解析、审核通过同事务入 Outbox、Inbox 重试/DLQ、限量回填。只对无标签可见帖处理；外部失败保留可重试状态，禁止写假标签。

```java
// 每次实际审核通过的事务内入队；LLM 只在异步消费者执行。
outboxEventService.createContentTopicTagEvent(contentId);
```

- [x] 定向标签/词表/审核/Inbox 17 项通过，Mapper 与真实回填运输在隔离 MySQL/MQ/Redis 及单帖 paid smoke 验证；提交阶段只选本任务文件。

## Task 2：④未来偏好同步与主服务快照重排

**Files:** QuantaBot `infra/qdrant_memory.py`、新增 `infra/profile_sync.py` 与 `memory/profile_preferences.py`、`composition.py`、`infra/settings.py`、`.env.example`；Java 新增 `dto/BotProfileEventDTO.java`、`entity/UserProfileSignal.java`、Mapper/XML、`service/ExplicitPreferenceService.java`、Impl、`controller/bot/BotProfileController.java`、MQ 配置/消息/消费者/生产者；主代理统一修改 `UserProfileService`/Impl、`RecommendRerankServiceImpl`、RecommendProperties、Outbox/Inbox 接线及 SQL。

**Interfaces:** Python `ProfileSyncWorker.run_once()` 只读取新挂 pending 的 Qdrant 点，缓存主服务词表，提取明确表达后提交上述 DTO；HTTP 在记忆锁外，成功清标记必须核对原 revision，失败保持 pending。Java `ExplicitPreferenceService.accept(BotProfileEventDTO)` 验证并同事务写事实+Outbox；`reconcile(Long userId)` 从当前最新有效记忆状态重建显式 Hash。`UserProfileService.getExplicitProfile(Long)` 返回 topic→有符号权重，默认空 Map 保持现有调用兼容。

- [x] RED：新增端口/实现前失败，收尾针对未知主题、HTTP 400、空结果及墓碑发现真实边界并窄修；保留失败说明，不把编译 RED 当完整行为证明。

```python
assert extract_preferences("我喜欢篮球", "positive", topics) == ("basketball",)
assert extract_preferences("我在计算机学院读书", None, topics) == ()
```

- [x] GREEN：不改 memory_ops 决策 prompt，在持久化点上挂新事件；后台重试接 BOT API；主服务事实持久化、快照校准、独立显式算分。正偏好增益、负偏好更强惩罚，候选不被硬删除；显式权重不进入 `__total` 或行为分母。

```java
double finalScore = alpha * normHot + (1.0 - alpha) * matchScore + explicitScore;
```

- [x] 定向 Python / Java 安全、事实、重排、真实 Redis 与 MQ 测试已通过；同事件 HTTP/MQ 重投不重复，DELETE 与旧版本不复活。Python 最后 UPDATE 重放覆盖 DELETE pending 的窄修单独7项回归，不重复整体评测。

## Task 3：必要验收、独立审查与文档收尾

**Files:** 本计划、`demo0/docs/api-test/RESULTS.md`、`demo0/docs/后续demo0优化总方案.md`、QuantaBot `docs/技术选型.md`、`总计划.md` 与适用 AGENTS 变更记录。只回写本次真实结果。

- [x] 运行风险相称的定向 Java 单元+真实 MySQL/Rabbit/Redis 集成组合；Python 定向14、unit259及 Ruff 通过，不跑付费 M5 门禁；独立审查发现的最后边界仅定向补测。

```powershell
mvn -f demo0/pom.xml '-Dtest=ContentTopicTagServiceImplTest,ExplicitPreferenceServiceImplTest,ProfilePreferenceIntegrationTests,BotProfileAuthorizationTests,UserProfileServiceImplTest,RecommendRerankServiceImplTest' test
uv run --project QuantaBot pytest QuantaBot/tests/unit -q
```

- [x] 一帖真实 LLM 标签 smoke 通过（experience_sharing + course_study）；一条合成未来偏好经真实 BOT 鉴权 HTTP→MySQL/Outbox SENT→MQ/Inbox SUCCESS→Redis→排序，HTTP/MQ复投幂等；真实组件验证更新/删除/旧版本，Qdrant 本地 SDK 验证 ADD/UPDATE/DELETE。生产外部网络全栈不以隔离验证冒称完成。
- [x] 用 requesting-code-review 派未参与实现的 reviewer，最终只读复核无未闭合 Critical/Important，Ready for delivery；初审两项 Important 已修。负向重排关键断言变异捕获 RED 后已恢复并验证 GREEN。
- [x] 自查任务文件及新增行秘密特征、无记忆原文事件、BOT/运营权限边界；更新计划复选框和唯一 RESULTS。区分小批验证与全量未执行；不宣称整个新版本通过 M5 Release。
- [x] 精确 git add 已核对的75个本任务文件，Conventional Commits 本地提交；交付路径、证据与未完成项。不 push、不合并，除非用户另行授权。

## 执行记录

- 2026-09-28：用户已确认简单计划及所有范围选择，并明确要求“写一个计划，然后开始执行”；无需重复等待执行方式选择。计划采用两个实现任务并行、一个验证收尾任务。
- 开始时 Docker ServerVersion 29.6.1 可读；本次后来执行的证据统一回写 RESULTS S-TP，不把此前 M5 证据当成本次证据。
- 用户补充后已暂停并完成词表对齐：26 项已确认；电子硬件替换为经验分享；每帖最多三个主题标签，可同时命中多个领域。现在恢复两个实现任务。
- 实施与验证结果统一见 RESULTS S-TP；独立初审两项 Important（重复任务、毒消息）已修。收尾发现的人工审核漏接、不可见假SUCCESS、pending空结果和乱序撤销均以窄回归收口。额度暂时中断子代理后主代理接手；未扩大到整轮M5或存量全量回填。
- 最终只读审查完成，无未闭合 Critical/Important；最后 Java 定向20项和 Python 撤销乱序定向7项均通过。原工作区未提交内容未改动，隔离分支本地交付，部署及全量存量回填仍属后续执行。
