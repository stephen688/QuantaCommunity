# AGENTS.md — demo0（QuantaCommunity 主服务）

> 本文件是 AI 编码智能体在 demo0/ 内工作的操作说明书。
> 适用范围：本文件所在目录及其子目录；仓库级 Git 操作仍在 QuantaCommunity/ 根目录执行。
> 关联文档：docs/outbox-plan.md（可靠事件）、docs/security-governance-plan.md（安全边界）、docs/api-test/TEST_PLAN.md（接口验收）、docs/api-test/RESULTS.md（执行结果）、../QuantaBot/AGENTS.md（Agent 侧规则）。
> 权威策略：本文件只维护稳定不变量和查阅路径；具体版本、状态机、运行参数和接口契约以指向的代码、配置、测试及专项文档为准，不在此复制副本。
> 变更记录：v0.1（2026-09-19）——初稿；按 Agent 侧 AGENTS.md 的结构建立主服务专属规则，补齐 Java/Spring、数据库事务、MQ、鉴权和测试差异。
> v0.1.1（2026-09-20）——可靠事件语义收敛到 §4.4；移除版本号与特定 skill 名依赖；补充 Outbox/Inbox 和审核链权威入口。
> v0.2（2026-09-20）——补齐命令前置条件、计划跳过判据、可检查的依赖方向、TDD 铁律和过渡态文档回写机制。
> 变更记录：v0.3（2026-09-20）——吸收企业 AI Coding 测试实践：§6.2 增测试策略分流（行为变更默认 TDD，非行为变更降档）与存量测试保护（禁删除/跳过/放宽断言，契约变化可同步修改）；§6.3 独立审查对高风险新增测试增人工变异抽查；交付摘要须显形 pom/CI/.gitignore 敏感改动。
> 变更记录：v0.4（2026-09-20）——§6.1 增 E2E 边界触发规则：跨 HTTP/DB/Outbox/消费者/审核/Bot 写库/可见性边界的改动必须执行端到端验证（自动化 E2E 落地前以 docs/api-test 阶段顶替）；E2E 断言最终业务结果、Outbox/Inbox 状态与幂等复投，不以 HTTP 200 代替；fake Agent 仅替换 LLM 决策，身份走真实鉴权；07-bot.http 真链路保留为发布门禁。
> 变更记录：v0.4.1（2026-09-20）——明确 TDD 降档例外与 RED-first 适用范围；规定变异抽查在临时副本中执行；明确接口契约测试表述并整理变更记录顺序。
> 变更记录：v0.5（2026-09-20）——§4.3 收紧两条风格规则（热榜缓存任务 review 反馈）：①注入——新增类必须沿用同包主流注入方式（@RequiredArgsConstructor + private final），仅构造期计算/校验（如 Caffeine 实例构建）允许手写构造器并注明原因；②注释——新增类必须有文件头注释（模块名+职责+边界），公开/复杂方法必须有 Javadoc，语义不自明的字段（如墓碑、占位值）必须有行内注释，交付自查将"无注释"列为与"无测试"同级不合规。
> v0.5.1（2026-09-28）——推荐③④词表/迁移/接口权威入口见 docs/plans/2026-09-28-recommend-topics-preferences.md；验收在 docs/api-test/RESULTS.md S-TP，存量全量回填不与增量实现混称完成。

***

## 0. 最高优先级（不可违背）

> 以下规则优先级高于一切实现细节。已有代码若与规则冲突，先记录现状和影响，再用最小改动修正；不要通过无关重构掩盖冲突。

1. **遵循既有风格和既定设计**：主服务后续代码必须先复用仓库已经形成的包结构、命名、Result/异常、鉴权、事务、Outbox/Inbox、MQ 和测试模式；不能因为换成 Java 就另起一套架构，也不能为了个人偏好替换已有方案。
2. **数据库是业务事实源**：MySQL 中的业务状态是权威；Redis、Elasticsearch、向量文件、Feed、热度和 WebSocket 都是派生状态或异步副作用，不能反过来覆盖未确认的业务事实。
3. **可靠事件遵循单一口径**：Outbox/Inbox 的事务、发送、幂等、租约、ACK 和重放不变量统一见 §4.4；本节不维护摘要副本，冲突时以 §4.4 及其指向的权威材料为准。
4. **鉴权边界清晰**：HTTP 请求在 Filter/Spring Security/Controller 门面完成认证和授权；MQ 消费者、Outbox Dispatcher、定时任务和共享 Service 不得假设存在 SecurityContext 或 HTTP BaseContext。
5. **审核不能绕过**：帖子、回答、评论和 Bot 回复必须遵循 §4.6 指向的审核、敏感词和人工分流链路。
6. **Bot 写库必须走主服务**：QuantaBot 只能通过主服务公开的鉴权和业务入口写入回复，不能直连 MySQL、绕过参数校验、权限、幂等或二次审核。
7. **接口兼容优先**：HTTP 状态码、Result.code、匿名可选鉴权语义、WebSocket 行为和现有前端依赖的字段，除非明确授权，不得在顺手重构中改变。
8. **秘密不进仓库**：Token、密码、JWT 密钥、云服务密钥、完整敏感 payload 和未脱敏生产数据不得写入源代码、测试 fixture、日志、Outbox payload、RESULTS.md 或 Git diff。

***

## 1. 项目背景（一行版）

demo0 是 QuantaCommunity 的 Java 主服务：负责用户与身份、内容/回答/评论、关系链、通知、管理治理、安全、WebSocket、搜索/RAG，以及向 QuantaBot 发布事件和接收 Bot 写库请求。

- 当前代码栈：Java、Spring Boot、Maven、Spring MVC、MyBatis、MySQL、Redis、RabbitMQ、Elasticsearch、Spring Security、WebSocket、Spring AI、SimpleVectorStore、阿里云 OSS/内容安全等；语言、框架和依赖版本以 pom.xml 为唯一权威。
- 默认 HTTP 端口：9191，以 src/main/resources/application.yml 的实际配置为准。
- 同仓库其他组件：../QuantaBot 是 Python Agent 服务；../demo0-admin 是管理端；../demo0-miniprogram 是小程序。主服务任务默认只改 demo0/，跨组件改动必须明确列出边界和联调证据。
- Agent 联调契约：主服务侧接口和事件以当前 Controller、消息模型、测试及 docs/api-test/cases/07-bot.http 为准；Agent 的人格、决策和 Agent 内部实现以 ../QuantaBot/AGENTS.md 与其项目文档为准，不在本文件复制一份。

***

## 2. 目录结构（骨架；以代码为准）

    demo0/
    ├─ src/main/java/com/quanta/demo0/
    │  ├─ controller/              # HTTP 门面；user/admin/bot 等接口边界
    │  ├─ service/                 # 业务接口
    │  │  └─ Impl/                 # 现有 Service 实现目录，大小写暂不做全局重命名
    │  ├─ mapper/                  # MyBatis Mapper 接口
    │  ├─ entity/                  # 持久化实体与兼容中的 BaseContext
    │  ├─ dto/                     # 请求 DTO / 输入契约
    │  ├─ vo/                      # 对外响应 VO
    │  ├─ result/                  # 统一结果与分页结果
    │  ├─ config/                  # Spring、MQ、Security、RAG、WebSocket 等装配
    │  ├─ security/                # Token、Filter、角色/权限和安全异常
    │  ├─ annotation/ + aop/       # 限流、审计等横切能力
    │  ├─ mq/
    │  │  ├─ message/              # 事件消息模型
    │  │  ├─ outbox/               # Outbox Dispatcher / 路由
    │  │  ├─ producer/             # 可靠发布与重试/死信
    │  │  └─ consumer/             # Inbox 幂等消费
    │  ├─ rag/                     # generation/retrieval/vector/model
    │  ├─ es/                      # Elasticsearch 适配
    │  ├─ properties/              # @ConfigurationProperties 配置对象
    │  └─ exception/ + handler/    # 业务异常和统一 HTTP 异常响应
    ├─ src/main/resources/
    │  ├─ application.yml          # 默认运行配置；密钥通过环境变量/本地 secret 提供
    │  ├─ mapper/*.xml              # MyBatis SQL
    │  ├─ db/*.sql                  # 数据库/验证/种子脚本
    │  └─ lua/                      # Redis Lua 原子操作
    ├─ src/test/                    # 单元、Web、安全、MQ、Testcontainers 集成测试
    ├─ docs/api-test/               # 分阶段 HTTP/API 测试、唯一结果记录和脚本
    └─ pom.xml

现有代码仍是按技术层分包；“按域分包”的重构属于单独计划，不得在普通功能任务中顺手迁移大量包名。新增代码先跟随被修改区域的既有结构，避免制造同一模块两套组织方式。

***

## 3. 命令

以下命令默认在 demo0/ 目录执行；Git 命令例外，统一在 QuantaCommunity/ 根目录执行。

- 环境检查：先确认 java -version、mvn -version 可用；当前 mvn test 会运行 MySQL/RabbitMQ Testcontainers 集成测试，执行全量测试前还需确认 docker info 成功。
- 编译：mvn -DskipTests compile。
- 单元/应用测试：mvn test。
- 定向测试：mvn -Dtest=SomeTest test；多个测试类按 Maven Surefire 支持的逗号形式传入。
- 可靠性集成测试：mvn -Dtest=ReliabilityMySqlIntegrationTests,OutboxRabbitIntegrationTests test；需要 Docker/Testcontainers，环境不具备时记录为 BLOCKED，不要改断言伪装通过。
- 打包：mvn -DskipTests package；默认产物为 target/demo0-0.0.1-SNAPSHOT.jar，以实际版本为准。
- 本地启动：mvn spring-boot:run，或先打包后执行 java -jar target/demo0-0.0.1-SNAPSHOT.jar。
- 配置准备：以 .env.example 和 src/main/resources/application-local-secret.yml.example 为模板，通过当前运行方式注入环境变量；真实密钥不写入模板和 Git。
- Phase 0-7 API 回归（Windows）：

      powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\api-test\scripts\run-phase.ps1 -Phase all

- Phase 0-7 全量脚本（Windows）：powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\api-test\scripts\run-full.ps1。
- API 脚本前置：MySQL demo、Redis localhost:6379/1 和 demo0 :9191 必须可用；涉及 ES、MQ、OSS 或 AI 的用例还要分别验证对应依赖，具体清单见 docs/api-test/TEST_PLAN.md。
- API 结果记录：执行结果唯一写入 docs/api-test/RESULTS.md；状态只能使用 PASS、FAIL、PARTIAL、BLOCKED、SKIP、PENDING，不得把“接口能访问”直接写成“依赖链路健康”。
- Bot 真联调：必须同时确认 demo0 :9191、QuantaBot :8000、RabbitMQ、Redis 及 Agent 所需依赖处于可复核状态，再按 docs/api-test/cases/07-bot.http 和 Agent 侧门禁执行；没有 HTTP→Outbox→MQ→Agent→写库→审核→可见性证据时，不得标记整条链路 PASS。
- 结果判定：Maven/脚本退出码为 0 才表示命令完成；非 0 时必须结合日志区分代码/断言失败与环境 BLOCKED，不能只凭退出码猜原因。

没有在 pom.xml 中配置 Checkstyle/Spotless 等独立格式门禁；不要在单个任务中擅自引入新的全局格式化工具。修改 Java、XML 或 SQL 时保持文件现有格式，并至少执行相关编译/测试。

***

## 4. 代码风格与规范

### 4.1 既有风格和设计（硬规则）

- 先读同目录、同层级和同业务链路的已有实现，再决定写法；已有模式优先于个人习惯、网上模板和新框架。
- 新增类、方法、字段、异常、Result、Mapper、配置和测试命名，必须与附近代码保持一致；除非任务本身就是重构，不要顺手改名或迁移。
- 既有的 Service → Mapper、Controller → Service、Spring 配置、Security Filter、BaseContext 兼容层、Result 响应、Outbox/Inbox 状态机和测试写法都是设计约束，不是可随意替换的示例。
- 网上资料只能用来补充通用做法，不能覆盖本仓库的代码、测试、配置和已确认文档；出现冲突时以本仓库既有可运行设计为准，并在计划/交付中说明。

### 4.2 分层纪律（硬规则，违反即打回）

- 依赖方向：HTTP Controller、MQ Consumer、定时任务等入口 → Service → Mapper；config/properties 只负责装配与配置。禁止 Mapper 反向依赖 Service，禁止 Service/Mapper/MQ 导入 Controller，禁止消费者或定时任务调用 Controller。
- Controller 只负责 HTTP 输入输出、参数校验、认证/权限门面和调用 Service；禁止 Controller 直接写 Mapper、拼接持久化 SQL 或操作 Redis/MQ/ES 客户端。
- Service 承担业务编排和事务边界；Mapper 只承担持久化访问；外部系统访问集中在已有的配置/适配/Service 边界中。
- dto 表示请求输入，entity 表示持久化模型，vo 表示对外响应；新接口不要为了省一个类把 Entity 直接暴露给前端。
- 配置优先通过 properties/ 中的 @ConfigurationProperties 注入；业务代码不得到处读取 System.getenv、System.getProperty 或散落的 @Value。修改已有代码时只在相关范围内收口，不做无关大迁移。
- 需要异步传播的业务变更，使用已有 Outbox 服务/路由，不直接在 Service 中裸调用 RabbitMQ。事务后的 Redis、ES、Feed、热度和 WebSocket 操作必须可重试或可由当前状态重建。
- RAG 业务通过 rag/ 现有边界访问检索、生成和向量存储；Controller 不直接创建 AI、ES 或向量客户端。
- 外部客户端不得在类加载或静态初始化阶段创建连接；沿用 Spring Bean 装配和现有配置生命周期。

### 4.3 Java/Spring 具体规则

- 版本：语言、框架和依赖版本只读 pom.xml，本文件不维护版本副本；升级框架、替换 MQ/Redis/数据库或引入新编排框架必须单独说明兼容性和验证范围。
- 注入：新增类一律沿用仓库既有注入风格——写新 Service/Component 前先看同包多数类：若普遍使用 Lombok @RequiredArgsConstructor + private final 字段，新类必须同样使用，不手写构造器赋值；仅当字段需要构造期计算/校验（如构建 Caffeine/客户端实例）时才手写构造器，且需在类注释说明原因。已有类若使用 @RequiredArgsConstructor，沿用现有方式，不为统一风格大面积改写。
- 命名：包名全小写，类/枚举 PascalCase，方法/字段 camelCase，常量 UPPER_SNAKE_CASE；禁止单字母或无法表达含义的变量名，循环索引例外。现有 service/Impl 路径的大小写暂不通过无关任务修正。
- 类型与边界：公开 Service 接口、Controller 入参/出参和消息契约使用明确类型；新增请求使用 Bean Validation；分页、ID、时间和状态字段保持现有类型与序列化格式。
- 异常：只捕获能处理的异常；禁止空 catch、捕获后返回假成功、把基础设施故障吞成空列表，或用通用 RuntimeException 覆盖原始原因。统一 HTTP 错误交给现有异常处理链。
- 事务：@Transactional 放在可被 Spring 代理调用的 Service 方法上；注意 self-invocation、异常类型和事务传播，不能只加注解而不验证实际回滚。
- SQL/MyBatis：SQL 放在对应 Mapper XML/既有 Mapper 约定中；参数必须绑定，不拼接未经约束的用户输入；修改分页、锁、唯一约束或索引时同时补测试和数据库说明。
- 日志：使用项目现有日志方式，记录 eventId、业务 ID、用户 ID、状态和降级动作等可定位信息；禁止记录 Token、密码、密钥、完整请求体或完整敏感内容。
- 注释：注释解释业务意图、并发边界、失败策略和为什么，不重复代码。核心业务链路按步骤保留简短行内注释，中文注释、英文标识符。**新增类必须有文件头注释**（模块名 + 职责 + 边界，格式参照同包既有类，如 TrendingDataLoader 的头注释）；新增公开方法和复杂私有方法必须有方法级 Javadoc，说明行为契约与失败策略；新增字段若语义不自明（如墓碑、占位值）必须有行内注释。交付自查时"没有注释"与"没有测试"同为不合规。
- API 响应：沿用 Result、现有分页对象和全局异常契约。认证失败为 HTTP 401，权限不足为 403，限流为 429，参数错误为 400；不要只修改 HTTP 状态而忘记响应体 code。

### 4.4 可靠事件不变量（唯一口径）

> 状态枚举、重试次数与间隔、租约、DLQ 和重放流转以 docs/outbox-plan.md §二.1、§二.2、§二.4、§二.5 为设计权威；运行参数以 src/main/resources/application.yml 和当前实现为准。若文档与代码不一致，先报告差异，不自行猜测。本节只维护跨版本不变量。

- 同一业务动作的 MySQL 业务写入和 Outbox 插入必须在一个事务中；事务回滚不能留下可发送的孤儿事件。
- 每个可靠事件使用稳定 eventId；同一事件重试不得生成新的业务事件 ID。消息模型缺少 eventId 时，按当前消费者约定拒绝、重试或进入死信，不要猜一个 ID 补上。
- Outbox Dispatcher 使用租约、locked_by/locked_until 和状态条件更新；接管与防旧实例覆盖的机制见 docs/outbox-plan.md §二.4。
- SENT 的定义是 Confirm ACK 且没有 Return；Confirm ACK 但路由失败仍属于发送失败。发送失败按配置重试，超过次数进入 DEAD，并保留可审计原因。
- 主服务消费者手动 ACK；业务处理、Inbox SUCCESS 和 ACK 的先后顺序必须与当前消费者实现一致。失败时进入重试/DLQ，不能先 ACK 再异步处理。
- Inbox 以 consumerName + eventId 幂等；租约接管、条件更新和 ACK 顺序见 docs/outbox-plan.md §二.5。
- Feed、热度、ES 等最终一致性消费者优先重新读取 MySQL 当前状态，再执行 upsert/delete；不要把乱序到达的旧消息当作最终真相。
- 管理员重放只针对允许重放的 DEAD 记录，必须使用条件更新防并发，并留下操作人、时间和原因；不要直接重放已成功事件制造重复业务结果。
- 可靠性口径是至少一次 + 幂等，不承诺恰好一次；测试必须覆盖重复投递、Confirm/Return、断连、租约接管、重试耗尽和重放竞争。

### 4.5 安全与上下文

- HTTP 认证由 OptionalJwtAuthenticationFilter 和 Spring Security 链路完成；现有请求同时维护 SecurityContext 与兼容中的 BaseContext 时，请求结束必须清理两者。
- URL 层负责基本登录边界，Controller/HTTP 门面使用 @PreAuthorize 或已有权限常量做细粒度授权；不要用前端按钮隐藏代替后端权限。
- @RabbitListener、@Scheduled、Outbox Dispatcher 和共享 Service 没有可靠的 HTTP SecurityContext；需要操作者时显式传递服务身份、事件来源或审计上下文，不能从 ThreadLocal 猜。
- 保留现有可选鉴权语义：推荐、搜索等允许匿名访问的接口，匿名、有效 Token、无效 Token 的行为必须先查现有测试/文档，未经授权不改成统一强制 401。
- 新增接口默认按最小权限设计，并同时补无 Token、伪造/过期 Token、普通用户、正确角色/权限和被封禁用户的测试。
- WebSocket 继续沿用 STOMP ChannelInterceptor 的认证边界；不要把 WebSocket 消息误当成普通 HTTP Filter 请求。
- Bot 入口使用服务 Token、BOT 角色和现有方法级权限；Bot 账号身份、评论写库、通知和审核状态都必须由主服务验证。

### 4.6 审核、RAG 与外部依赖

- 审核权威入口：配置见 application.yml 的 quanta.moderation 和 AliyunModerationProperties；执行链见 SensitiveWordChecker、ContentModerationServiceImpl、ModerationConsumer、ModerationResultServiceImpl；环境接入见 docs/moderation/01-environment-setup.md。
- 内容审核开关、目标类型、自动驳回、疑似转人工和关闭策略都从上述配置与实现读取；不要在 Controller 中硬编码另一套规则。
- Bot 回复走普通评论业务写入和主服务二次审核路径；“生成成功”不等于“可以公开可见”。
- AI、OSS、审核、ES、Redis、RabbitMQ 任一外部依赖失败时，按业务定义降级、重试或阻断，并留下可定位日志；不能用空结果掩盖错误。
- RAG 检索为空、向量文件缺失或 AI 不可用时，必须遵循当前服务的明确降级语义，不编造“已检索到”的来源或健康状态。
- 所有外部依赖的真健康必须用依赖级证据验证；/health 或一个 HTTP 200 只能证明接口层，不足以证明 MQ、Redis、ES、数据库和审核链路全部可用。

***

## 5. Do / Don't（已确定项）

**Do**

- 在同一事务中完成业务写入和 Outbox 写入；对 Redis/ES/Feed 等事务外副作用提供重试、重建或校准路径。
- 为每个异步事件保留 eventId，用 consumerName + eventId 做 Inbox 幂等，并校验租约所有权。
- 把 401、403、429、业务 400 和响应体 Result.code 的边界写进测试，而不是只看前端是否“有反应”。
- 对 Bot 链路保留 commentId、eventId、触发/回复 ID、审核状态和可见性证据；重投后验证只有一条有效回复。
- 使用 Testcontainers/真实依赖测试验证 MySQL 事务、RabbitMQ Confirm/Return、断连恢复和重复消费；不能只用全 mock 证明可靠性。
- API 测试统一更新 docs/api-test/RESULTS.md，把环境阻塞和产品失败分开记录。
- 发现旧代码的相邻问题时，若不属于当前范围，记录在交付摘要或问题文档中，不顺手扩大改动面。

**Don't**

- 不在 Controller、定时任务或 MQ Consumer 中直接写数据库并裸发 MQ。
- 不把 Redis、ES、向量文件或消息 payload 当成唯一业务真相，也不通过缓存写入替代 MySQL 事务。
- 不在 Confirm ACK 但消息 Return、租约失效或数据库状态更新失败时标记 SENT。
- 不在消费者中先 ACK 后处理，不通过新增随机 eventId 绕过 Inbox 幂等。
- 不在后台线程、MQ listener 或定时任务中依赖 HTTP 的 SecurityContext/BaseContext。
- 不绕过审核、权限、限流、参数校验或 Bot 服务身份；不让 QuantaBot 直连主服务数据库。
- 不为了“跑通”修改测试断言、跳过失败的集成测试、关闭安全开关或把 BLOCKED 改成 PASS。
- 不在本任务中顺手把 MVC 分包改成 DDD/微服务，不无理由升级 Java/Spring/ES/RabbitMQ，不引入 Kafka、分布式事务或“恰好一次”承诺。

***

## 6. 编码工作流（一个任务，一个计划文件从开始到结束的动作序列）

> 核心闭环：读现状与计划 → 明确边界 → 先写可失败测试 → 最小实现 → 分层验证 → 自查/审查 → 诚实交付。纯文档措辞、配置说明和一次性脚本可跳过代码 TDD，但仍要检查链接、命令和事实准确性。

### 6.0 计划阶段（Planning；新任务/大功能必经）

满足任一条件即先写 docs/plans/<任务>.md：跨两个以上分层；新增公开接口/消息字段/数据库表索引；改变认证、审核、Outbox/Inbox、幂等、缓存一致性；或预计需要多次独立验证。

仅 typo、纯文档措辞、单文件局部 bug 或不改变契约/状态机/数据结构/安全与 MQ 语义的配置说明，可跳过 §6.0，直接进入 §6.1；拿不准时按需要计划处理。

1. 读权威材料：先读本文件 §0、§5，再按任务读取 docs/outbox-plan.md、docs/security-governance-plan.md、docs/api-test/TEST_PLAN.md 和相关代码/测试；涉及 Agent 联调时读取 ../QuantaBot/AGENTS.md 对应章节。
2. 对齐需求：需求有多种合理解释时，逐项确认范围、数据流、失败模式和成功标准；能从代码/文档确认的事实不要反复问用户。
3. 写可执行计划：把任务拆成带精确路径、测试目标和回滚边界的小步；计划需要用户确认时，未确认前不进入高风险实现。
4. 数据库/MQ/安全变更单独标边界：说明迁移脚本、兼容窗口、回滚方式、真实依赖要求和 API/前端影响；不把环境阻塞藏在“测试通过”后面。

### 6.1 动手前（Before coding）

1. 看根仓库状态：在 QuantaCommunity/ 执行 git status --short --branch，识别已有修改；不得覆盖用户已有改动。本仓库当前包含多个组件，提交和 diff 必须看清路径归属。
2. 先读再改：阅读目标 Controller、Service、Mapper/XML、配置、调用方、测试及相关文档；不要仅凭类名猜数据流。
3. 确认契约：先确认当前响应字段、权限、事务、事件类型、队列、状态机和回滚语义，再决定修改点。
4. 跟随现有模式：复用现有 Result、异常处理、@ConfigurationProperties、Outbox/Inbox、Security 和测试辅助类；不因个人偏好换一套框架。
5. 明确验证档位：提前区分编译/静态、单元、Testcontainers 集成、API/浏览器验收、依赖健康和环境阻塞，避免最后才发现缺少真实依赖。
6. E2E 边界触发（当前自动化 E2E 尚未落地，规则先行生效）：当修改跨越 HTTP、数据库、Outbox/MQ、异步消费者、审核、Bot 写库或公共可见性边界时，必须执行覆盖受影响边界的端到端验证——自动化 E2E 落地前，执行对应 docs/api-test 阶段并如实记录；纯业务逻辑、Mapper 或 DTO 变更可只做单元/集成验证。E2E 断言最终业务结果、Outbox/Inbox 状态和幂等复投结果，不以 HTTP 200 代替链路证据。fake Agent 只替换 LLM 决策，身份与写库必须走真实鉴权和主服务入口，禁止直连数据库伪造结果；07-bot.http 全链路（真实模型/机审/Langfuse）保持手工/发布门禁档，不进 Maven 测试。

### 6.2 写码中（While coding）

1. **测试策略分流**：行为变更（业务逻辑、状态机、事务、权限、缓存一致性、错误分支）默认 TDD；纯文档措辞、日志文案、注释、一次性脚本可降档为"先实现后验证"，但必须跑编译/定向测试或完成对应文档验证；新增 DTO 字段和公开接口字段按契约变更处理（§6.0 计划 + 服务端与调用方/接口契约测试）。分流判断在交付摘要中用一行说明，拿不准时按 TDD 处理。
2. 步内红绿节拍：对于按第 1 条判定为行为变更的可测试生产代码，必须先运行并确认"因预期原因失败"的测试，再写实现；先 RED，再写最小实现到 GREEN，最后 REFACTOR。禁止修改正确断言，或 mock 掉鉴权、审核、幂等和可靠事件边界来伪装通过。第 1 条允许降档的非行为变更按其对应验证要求执行。
3. 小步验证：每完成一个可测小步，至少运行目标测试或 mvn -DskipTests compile；跨事务、MQ、安全边界的改动不得只跑编译。
4. 最小正确改动：只改当前任务需要的文件；不顺手格式化整包、不重命名整个分层、不修改小程序/管理端和无关未提交文件。
5. 保持可靠性不变量：检查事务边界、Outbox/Inbox 状态、ACK 时机、租约所有权、权限入口和错误分支；每个失败路径都要有可观察结果。
6. 不编造证据：命令没有实际执行就写"未验证"；外部依赖失败就标 BLOCKED，不要用 mock 结果替代真实链路结论。
7. 文档同步：行为、接口、事件、配置或验收口径变化时同步相关文档；RESULTS.md 只记录真实执行结果，不写计划当结果。
8. **存量测试保护**：已存在的测试不得删除、跳过（@Disabled/@Ignore）或放宽断言来让改动通过；契约确实变化时允许同步修改受影响测试，但必须在交付摘要中单独列出改动文件和理由。新增测试不受限。修改 pom.xml 的 surefire/jacoco 配置、CI 脚本、.gitignore 属敏感改动，必须在交付摘要单独说明，不得与其他改动混入同一 commit。

### 6.3 完成后（After coding）

1. 代码变更验证：至少执行 mvn test 或与风险相称的编译/定向/集成测试组合；修改 SQL/Mapper 必须验证 Mapper 解析和相关 Service；修改安全/MQ/事务必须补对应边界证据。
2. API/联调验证：接口行为变化时执行对应 docs/api-test 阶段；Bot 链路变化时记录 HTTP、Outbox、MQ、消费/写库、审核和可见性证据。
3. 分层检查：分别报告编译、单元、集成、API、浏览器/设备和依赖健康结果；不要用一个绿色 HTTP 响应覆盖未验证的下游依赖。
4. 自查 diff：每行改动能对应需求；删除自己引入的未使用代码；确认没有密钥、Token、完整敏感 payload、临时日志和无关格式化。
5. 独立审查：跨模块、可靠性、安全、数据库或公开契约改动完成后，由未参与实现过程的审查者检查需求、diff 和验证证据；纯文档措辞类改动可免，但仍需自查事实和命令。审查涉及安全、事务、MQ、幂等、缓存一致性的新增测试时，做人工变异抽查：在临时副本或可恢复补丁中将断言中的关键值改为错误值运行，测试应当变红；不会变红的测试视为断言不足，补强后恢复原测试文件。不得把人为变异或抽查残留留在工作区。
6. 状态诚实：把失败、部分通过、跳过和环境阻塞分别写清；未完成的门禁不能用“代码已实现”替代。
7. 提交前回写：消灭本次范围内已解决的 [待确认]、TODO 和过渡态描述；确实未解决的必须写明阻塞条件与后续入口。如决策变化，先更新对应计划/权威文档，再更新本文件变更记录，不能只改 AGENTS.md 副本；未经授权不 push、不修改其他组件。
8. 交付摘要：说明改了什么、列出改了或是创建了哪些文件名（供我review）、输出结论、审查发现与处理、未完成事项及复现/继续方式。

### 6.4 Git 约定

- demo0/ 不是独立 Git 仓库；所有 status、diff、log、branch、add、commit 在 QuantaCommunity/ 根目录执行，并使用 demo0/ 路径审查范围。
- 保留用户和其他任务的未提交修改；不使用 git reset --hard、git checkout -- 覆盖工作区。
- 新分支默认遵循仓库/会话约定使用 codex/<模块>-<一句话>；若用户明确指定 feat/、fix/ 或已有分支，按用户要求执行，不批量重命名现有分支。
- 提交使用 Conventional Commits：feat、fix、docs、refactor、test、chore；可靠性、安全、幂等和迁移相关提交信息写明影响面，便于回滚。
- 完成一个可独立验证的小块再提交，不攒无边界的大 commit；未经用户授权不 push。

***
