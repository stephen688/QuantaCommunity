# 管理端功能补齐 Implementation Plan

> **For agentic workers:** 按任务使用子 agent 实现并由独立 agent 审查。writing-plans 所引用的 superpowers:subagent-driven-development / executing-plans 未安装，本计划使用当前会话的原生 collaboration 工具执行同一任务及审查闭环。

**Goal:** 补齐当前管理端权限入口、角色管理、正式登录、政策知识库及审核工作台，使已有后端能力能由实际管理员完整操作。

**Architecture:** 延续 Vue 3 / Element Plus / Pinia 和主服务 package-by-feature 的 Controller → Service → Mapper。权限及角色以服务器安全上下文为准，复用 JWT/Redis 会话与既有政策文档水位线同步，不另建身份、知识库或事件事实源。

**Tech Stack:** Vue 3、Vite、Element Plus、Pinia、Java 17、Spring Boot 3.5、Spring Security、MyBatis、MySQL、Redis、JUnit、Node 内置测试运行器。

**Spec:** 本文件 §1，来源为 2026-10-03 用户要求“把管理端需求补齐的项做一个计划，然后合理派遣子agent开始执行”及上一轮六项管理端审查结果。

## Global Constraints

- 遵循 demo0/AGENTS.md：原有包名、Service、DTO/VO、Result、鉴权、审计和事务风格保持一致。
- 保护当前大量未提交的教学注释及业务修改；不覆盖原文件、不重置、不全仓格式化、不提交用户原有变更。
- 本轮只改 demo0-admin 与为管理端必要的 demo0 API；小程序与 QuantaBot 不在实现范围。
- 行为改动先运行可失败测试，再最小实现。测试针对真实权限、请求契约、用户可见状态和持久化结果，禁止仅 grep 源文件冒充行为验证。
- 新接口验证无 Token、无权限、正确角色及非法输入；新增 Java 类与复杂方法有中文职责/边界注释。
- 子 agent 独占任务文件；公共路由和侧栏由 agent A 管理，登录、会话及安全配置由主 agent 管理。
- Maven 共享 target，所有执行必须使用命名互斥锁 Local\QuantaAdminMaven；主 agent 汇总后统一运行组合回归，避免并发编译污染报告。
- 不安装新的生产依赖；不引入动态权限系统；不自动执行生产迁移、重放历史事件、付费模型全量回填或发布。
- 真实验证分清编译、单元/契约、MySQL/Redis/API、浏览器和 Bot 下游同步；HTTP 200 不等于向量检索已生效。

## 1. 需求与验收口径

| 编号 | 需求 | 最终行为 |
|---|---|---|
| R1 | 举报管理权限修复 | CONTENT_READ_ADMIN 能访问举报页面，CONTENT_AUDIT 能处理，其他用户被拒；不新增 REPORT_HANDLE |
| R2 | 事件查看与重放分离 | EVENT_READ 能访问事件中心，EVENT_REPLAY 才能执行重放；运营管理员只能查看 |
| R3 | 用户角色管理 | 查询指定用户真实角色；ROLE_MANAGE 才显示授予/撤销操作；支持三种已有管理角色、确认、错误展示及操作后刷新；错误不伪装为空角色 |
| R4 | 实际管理员登录 | 取代默认 test/code 的主登录流程，绑定现有用户并复用服务器角色、封禁和 JWT/Redis 会话检查；开发联调入口仅开发环境可见 |
| R5 | 政策知识库页面 | 分页、关键词/状态筛选、详情、新增/编辑、软删除和恢复、受控 JSON 导入；展示“源文档已保存、由现有 Bot 增量同步”真实口径 |
| R6 | 审核工作台 | 增加待审核评论数量与跳转；按权限请求和展示任务，不对无权限模块产生误报 |
| R7 | 管理端退出会话 | 请求 POST /admin/auth/logout 成功后清本地状态；原子撤销匹配令牌，保留并发新会话及封禁标记；失败允许明确选择仅退出本机 |

登录方式于计划开始时询问用户：推荐账号密码绑定现有用户；微信扫码需要小程序参与。用户答复前先推进 R1/R2/R3/R5/R6，R4 设计暂按账号密码方案准备，具体实现与迁移由主 agent 在答复或合理等待后收敛并回写。

## 2. 并行执行与文件所有权

| 执行者 | 任务 | 独占文件 |
|---|---|---|
| agent A：权限/工作台 | Task 1 | demo0-admin/src/router/index.js、layouts/DefaultLayout.vue、views/report/*.vue、views/dashboard/Dashboard.vue、views/event/EventCenter.vue、独立前端权限测试/工具 |
| agent B：角色管理 | Task 2 | demo0-admin/src/views/user/UserList.vue、src/api/adminRole.js、角色辅助/测试；demo0/platform/security 的 AdminRoleController、AdminRoleService、AdminRoleServiceImpl 及专用测试 |
| agent C：政策知识库 | Task 3 | 新 PolicyDocList.vue 与 policyDoc.js、内容域政策管理 Controller/Service/DTO/VO/Mapper 及专用测试；现有 BotContentSyncService 仅按必要范围复用 |
| 主 agent | Task 4/5/6 | 登录、会话、安全配置、必要凭据模型/迁移、计划/验收记录、公共接线与最终验证 |
| 独立审查 agent | Task 6 | 只读审查本轮范围及证据，不改代码、不派遣更多 agent |

agent A 可提前增加 /knowledge 路由，权限按 OPERATIONS_ADMIN 角色判断；政策写接口沿用现有运营角色契约，SUPER_ADMIN 单独角色不自动冒充运营角色。公共路由角色数组语义为任一匹配，服务端始终强制检查。

## 3. Task 1：权限入口与待审核评论（agent A）

**Files:** 修改 §2 列出的 A 文件；新增 `demo0-admin/tests/admin-access.test.mjs` 与必要 `src/utils/admin-access.js`。

**Interfaces:** 输入服务器 `roles: string[]`、`authorities: string[]`；举报读 CONTENT_READ_ADMIN、举报写 CONTENT_AUDIT、事件读 EVENT_READ、事件写 EVENT_REPLAY；新增知识页路由 `/knowledge`。

- [x] 先写失败测试：真实生产访问判定函数输入审核员、运营、超管权限，验证举报入口、事件只读、无权限重放及知识页运营角色边界。
- [x] `node --test tests/admin-access.test.mjs` 运行 RED；如必要用 Vue SFC 编译器执行 script/setup 行为，禁止只断言源码包含某字符串。
- [x] 实现最小权限接线，重放按钮和方法双重前端门槛；管理端路由、侧栏和工作台口径一致。
- [x] 添加 pendingComment，通过 `/admin/comment/page?auditStatus=0&pageSize=1` 获取，失败显示错误而非零；按 CONTENT_READ_ADMIN 读取。
- [x] 新增 `/knowledge` 路由/菜单，延续现有视觉组件；交付后通知主 agent 可以修改侧栏退出逻辑。
- [x] GREEN，报告修改文件、RED/GREEN 与剩余风险；由主 agent 安排独立审查。

示例消费行为：
```js
assert.equal(canAccessAdminPage({ authorities: ['EVENT_READ'], roles: ['OPERATIONS_ADMIN'] }, { authority: 'EVENT_READ' }), true)
assert.equal(canAccessAdminPage({ authorities: ['EVENT_READ'], roles: ['OPERATIONS_ADMIN'] }, { authority: 'EVENT_REPLAY' }), false)
```

## 4. Task 2：角色读取与管理（agent B）

**Files:** `demo0/src/main/java/com/quanta/demo0/platform/security/controller/admin/AdminRoleController.java`、`service/AdminRoleService.java`、`service/impl/AdminRoleServiceImpl.java`；`demo0-admin/src/views/user/UserList.vue`、`src/api/adminRole.js`；新增角色读取/安全及前端操作测试。

**Interfaces:** `GET /admin/roles/user/{userId}` → `Result<List<String>>`，需要 USER_READ_ADMIN；授予/撤销保持既有 POST/DELETE `/admin/roles/{userId}/{roleCode}` 与 ROLE_MANAGE。

- [x] 先写角色 GET 与权限失败测试（当前应为 404；普通用户 403、无 Token 401）；服务层验证用户存在，读取已有 UserRoleMapper。
- [x] 用互斥锁运行目标 Maven 测试，确认 RED；新增类须有职责注释。
- [x] 补 Controller → Service 读取；保留既有审计、角色白名单和 afterCommit 会话/缓存失效。
- [x] 前端角色读取错误显示可重试状态；禁用授权按钮直到读取成功。角色变更前确认，成功后刷新；若修改当前登录用户导致会话撤销，应进入重新登录，而不是继续请求已失效会话。
- [x] 保留服务端“不能撤销自己唯一超级管理员角色”的约束，前端提示；不新增 BOT 等授权项。
- [x] GREEN；报告真实 API 合约与前端交互验证，由主 agent 安排独立审查。

示例 HTTP 验收：
```http
GET /admin/roles/user/1
authorization: <USER_READ_ADMIN token>
# 200，data 为角色代码数组；普通用户 403；不存在用户按既有业务异常返回
```

## 5. Task 3：政策知识库管理（agent C）

**Files:** 新增 `demo0-admin/src/api/policyDoc.js`、`src/views/knowledge/PolicyDocList.vue` 及独立测试；新增内容域 `controller/admin/AdminPolicyDocController.java`、公开管理 Service 与 impl、查询 DTO、管理 VO；扩展现有政策 Mapper，复用 BotPolicyDocDTO 与政策写流程。

**Interfaces:** `GET /admin/knowledge/policy-docs/page` → `Result<PageResult>`；`GET /admin/knowledge/policy-docs/{docId}` → 管理 VO；`POST /admin/knowledge/policy-docs` → 无数据 Result；`DELETE /admin/knowledge/policy-docs/{docId}` → 无数据 Result。运营角色与现有 `/bot/knowledge/policy-docs` 一致。

- [x] 失败测试验证分页/筛选、参数绑定、未知文档、权限及新增/软删后水位线；测试使用实际 DTO/Service 行为，Mapper 在 MySQL 集成验证。
- [x] RED 后补最小 API；分页夹紧 1..100、稳定更新时间/ID 排序；明确 active/deleted 状态，软删除保留墓碑。
- [x] 写操作复用既有校验与更新 create_time/update_time，必要事务与审计沿用既有模式。避免两套政策写逻辑；不自动调用 Agent /admin/ingest。
- [x] 页面使用 PageHeader、TableEmptyState 与现有列表/抽屉/表单模式；docId 编辑时不可改；删除需确认；已删除文档恢复需明确确认后 upsert。
- [x] JSON 导入接受 `[{docId,title,content}]` 或 `{documents:[...]}`，先预览及校验，限制 20 条、1 MiB、重复 docId 拒绝；逐条提交并展示成功/失败结果，失败行可重试，不伪称批量原子提交。
- [x] 页面只展示源文档状态和保存时间，明确下游同步由 Bot 增量任务完成；未查询向量库时不显示“已同步”。
- [x] GREEN；报告文件与测试证据，由主 agent 安排独立审查。

示例导入数据：
```json
[{"docId":"policy:admin-smoke","title":"管理端验收文档","content":"本条仅用于隔离验收，完成后通过管理 API 软删除。"}]
```

## 6. Task 4：正式登录（主 agent）

**Files:** 新增 security 域 AdminPasswordLoginDTO、AdminPasswordLoginController/Service/Impl、AdminCredential/Mapper、AdminPasswordConfiguration、`db/V_admin_credentials.sql` 与凭据初始化工具；修改 `SecurityConfiguration.java`、管理端 `api/auth.js`、`views/login/Login.vue`；复用 UserQueryService、SessionService 和 UserLoginVO。用户已确认账号密码方案；小程序真实微信登录保留，凭据绑定已有 user_id，仍沿用既有每用户单会话。

- [x] 收敛用户选择；账号密码方案采用独立凭据表绑定现有 user_id，BCrypt 单向哈希、不新增用户密码字段、不自助注册、不赋予角色。
- [x] 先运行失败测试：凭据错误不签 Token、被封禁/已删用户/无实际管理角色不能登录、正确凭据复用会话；新增 `/admin/auth/login` 为精确匿名白名单并限流，保持 `/user/login` 兼容。
- [x] 最小实现，统一失败提示；不记录密码，不在前端持久化密码；开发 code 入口仅在 DEV 环境显示且默认不选择。
- [x] 提供凭据初始化方式、迁移与回滚说明；不生成默认密码、不把真实凭据提交仓库。
- [x] 验证登录 → 安全上下文 → 管理页面，与错误/封禁路径；用户数据和角色不可由提交表单指定。

## 7. Task 5：管理端退出（主 agent，A 完成后）

**Files:** `demo0-admin/src/api/auth.js`、`src/layouts/DefaultLayout.vue`、admin-session 工具与测试、AdminPasswordLoginController、SessionService/Impl。旧 `/user/logout` 兼容接口会吞基础设施错误且删除封禁标记，因此本轮管理端改用专用 `/admin/auth/logout`，不修改旧接口契约。

- [x] RED：API 成功时先请求服务端撤销、再清本地；非 401 的网络/服务错误不得提示“已退出全部会话”。
- [x] 实现等待态和防重复提交；失败提示可重试或明确选择“仅退出本机”；401 可直接清理本地。
- [x] GREEN；验证旧 token 在服务器会话撤销后不可继续访问。

## 8. Task 6：集成、独立审查与交付（主 agent）

- [x] `npm run build`，所有新增 Node/Vue 行为测试；不为了此任务重复小程序/Bot 无关回归。
- [x] 运行必要新增后端定向测试；SQL 与会话变更用真实 MySQL/Redis/HTTP 验证。用户明确禁止过度测试，因此不扩大到既有整组或全仓回归。
- [x] API 真实验收使用隔离测试用户与政策文档：读/写权限、角色变更后旧会话失效、政策新增→编辑→删除→同步墓碑、密码登录→登出后旧 token 失效。
- [x] 浏览器基础验收：生产构建显示账号密码表单，无开发 code 入口，空提交显示必填提示。按用户禁止过度测试的要求，完整登录后菜单/角色抽屉/政策导入交互不扩展验证；与真实 API 证据分别记录。
- [x] 独立审查者逐项检查 R1..R7 与本轮文件变更；对安全新增会话测试做一次临时副本变异抽查，已恢复变异文件。
- [x] 修复审查问题，仅重跑受影响检查；更新本计划 checkbox 和 `demo0/docs/api-test/RESULTS.md`。
- [x] 交付文件、命令、结果和未验证项；没有真实向量检索证据不宣称 Bot 知识检索已更新，不 push/发布。

## 9. 回滚与验证边界

- 前端可逐模块撤回本轮补丁；后端新增管理 API 不改变既有用户/Bot接口。
- 角色管理复用 user_role，不改角色映射；授权失败不持久化页面状态。
- 政策页面操作真实源文档，必须通过软删 API 清理验收夹具，保留墓碑及同步证据。
- 账号密码表为增量迁移；回滚先禁用管理密码入口，再撤部署迁移；不删除原用户或角色。
- 当前工作树已有大量业务和教学注释修改，review 以本轮基线快照与明确文件清单为准，不能用整个 HEAD diff 当作本轮改动。

## 10. 执行记录

- 2026-10-03：按权限/工作台、角色管理、政策知识库分派 A/B/C；主 agent 完成账号密码与可靠退出。用户确认密码方案，后续要求禁止过度测试，验证收敛为新增功能的定向检查。
- 最终生产 Vite 构建通过；前端既有新增 Node 18 项通过。角色管理后端 5 项、政策管理后端 6 项、凭据初始化 3 项定向验证通过。
- 六条新增真实 HTTP/MySQL/Redis API 用例通过；独立审查发现举报复合删除绕过独立删除权限，已补两 Controller 的 CONTENT_DELETE 门槛并定向验证 1/1 通过。角色抽屉异步竞态已加请求代次/用户/关闭状态保护，静态复核通过，专用真实 SFC 定向检查 1/1 通过。
- 未执行生产迁移、未创建真实管理员凭据、未发布；POM 相对本轮基线没有内容变动，无新增生产依赖。
- 最终独立审查完成：无阻断问题。额外修复政策 ID 查询/写入/成功审计规范化不一致，一个原有定向用例以空白输入先复现，再 GREEN 1/1 通过；局部独立复核通过。
