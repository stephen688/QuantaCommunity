# demo0 API 测试计划（执行说明）

> 完整设计见项目内测试计划文档。执行结果**仅**写入 [`RESULTS.md`](RESULTS.md)，勿另建 `results-*.md`。

## 前置条件

| 依赖 | 说明 |
|------|------|
| MySQL `demo` | 全部接口 |
| Redis `localhost:6379/1` | JWT 与 `login:token:{userId}` 绑定 |
| demo0 端口 **9191** | `BASE_URL=http://localhost:9191` |
| ES / MQ / OSS / AI | 部分用例；失败标 `BLOCKED` |

## 环境变量

1. 复制 [`env.example.sh`](env.example.sh) → `env.sh`
2. `source env.sh`
3. 每阶段将接口返回的 `data.id` 等写回 `env.sh`，并同步更新 `RESULTS.md` 对应 §节

## 分阶段顺序

| 阶段 | 内容 | 主要产出变量 |
|------|------|----------------|
| Phase 0 | 启动服务、MySQL/Redis | `USER_TOKEN`, `ADMIN_TOKEN` |
| Phase 1 | 登录与鉴权 | 验证 401/403 与 `body.code` |
| Phase 2 | 上传、发布内容 | `IMAGE_URL`, `CONTENT_ID_PROF`, `CONTENT_ID_LIFE` |
| Phase 3 | 管理端审核 | 审核通过的专业帖供下游读/写 |
| Phase 4 | 读接口 | 推荐、详情、搜索、RAG 等 |
| Phase 5 | 写操作与业务规则 | `ANSWER_ID`, `COMMENT_ID` |
| Phase 6 | 管理端治理 | 封禁、举报处理等 |
| Phase 7 | 破坏性清理 | 仅删除测试数据 |
| Bot-0 | Task 13 真栈前置 | `07-bot.http` 的 B0/B1/B2 鉴权、契约和单条 E2E |
| Bot-1 | Task 14 回归 | `07-bot.http` 的 S01-S17 人格场景；未有真证据不得勾 M3 |

## 登录 Mock

```bash
curl -s -X POST "$BASE_URL/user/login" \
  -H "Content-Type: application/json" \
  -d '{"code":"test"}'
```

从响应 `data.token` 填入 `USER_TOKEN`。

## 目录说明

| 路径 | 用途 |
|------|------|
| `RESULTS.md` | 64 接口唯一执行记录 |
| `env.example.sh` | 环境变量模板 |
| `cases/` | 可选 curl / `.http` 用例（`01`–`02` 鉴权/造数/审核；`03-read`/`04-write` 读写；`05-admin`/`06-destructive` 治理/清理；`07-bot` QuantaBot 联调） |
| `scripts/run-phase.sh` | **主入口**（Git Bash / Linux / macOS）：curl + jq，写 `env.sh` + `test-output/last-run.json`；连续 3 次同类失败暂停 |
| `scripts/run-phase.ps1` | Windows 备选（同上能力，无 jq 依赖） |

## Agent 自动执行

```bash
cd demo0/docs/api-test
./scripts/run-phase.sh all    # 或: 0-1 | 2-3
```

Windows 无 Bash 时：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-phase.ps1 -Phase all
```

执行后由 Agent 根据 `test-output/last-run.json` 更新 `RESULTS.md`。

## 判定规则

- **拦截器**：无 Token / 无效 Token → HTTP **401**；普通用户访问 `/admin/**` → HTTP **403**
- **业务**：看 JSON `code` 与 `msg`（200 成功；400/401/404/500 见 `GlobalExceptionHandler`）
- **contentType**：以 Service 为准 — `1`=生活求助，`2`=专业问答

## 认证、详情与 Feed 作者缓存功能验收（S-RC）

权威设计与功能门禁见 [统一缓存计划](../plans/2026-09-20-auth-detail-feed-read-cache.md)。压测另归 [总方案第 7 项](../后续demo0优化总方案.md#jmeter-load-testing)，不属于本轮功能验收。

在 `demo0/` 执行前确认 Java、Maven 与 `docker info` 成功。新增真实 Redis 与运行态测试使用 Testcontainers 的临时 Redis/MySQL/RabbitMQ、随机 HTTP 端口和独立测试数据，不暂停共享 Redis、不写入本机现有业务数据库；HTTP/STOMP 必须走生产认证、Controller 与缓存业务服务。

```powershell
mvn -Dtest=ContentDetailCacheRedisIntegrationTests,ContentDetailCacheWritePathTest,UserReadCacheWritePathTest,ReadPathCacheLocalExpiryTests,ReadPathCacheRuntimeIntegrationTests test
mvn test
```

定向命令补验真实 Redis JSON/TTL/墓碑/竞态/故障恢复、业务写路径提交与回滚、双实例本地缓存过期，以及 HTTP/STOMP 真栈。双实例 TTL 测试缩短测试配置以验证过期机制，生产默认值仍以 `ReadPathCacheProperties` / `application.yml` 为准；不能据此声称等待了生产 TTL 或实现了跨实例广播。命令、测试数、退出码、原始报告及各层证据统一归档 [RESULTS.md 的 S-RC 小节](RESULTS.md#read-path-cache)，没有通过的项不得用其他测试代替。

## Bot 主链路验收（Task 14）

`cases/07-bot.http` 是独立的 QuantaBot 联调套件，不替代既有 Phase 0-7 用例。执行前必须满足：

1. Task 13 已启动 demo0 `:9191`、QuantaBot `:8000`、RabbitMQ、Redis、Qdrant、Langfuse，并确认 `/health` 为真模式且依赖均为 `ok`。
2. 先运行 `01-auth-user.http` 取得 `userToken`；`adminToken` 必须具备运营管理员权限；`botToken` 只能从本机 `cases/http-client.private.env.json` 注入。
3. 先用一个真实可见帖子 ID 填 `botTestContentId`；B2-01 运行后由响应脚本写 `botTriggerCommentId`，等待异步链路完成后再跑 B2-02~B2-04。
4. IntelliJ HTTP Client 不做无界轮询；异步链路 60 秒内未可见即记录 `FAIL/BLOCKED`，并回到 Task 13 五段证据定位。`B1-06b` 使用 B1-05 生成的本地当前时间回拨水位线（`yyyy-MM-dd HH:mm:ss`，URL 编码）与 `/bot/content/sync` 的 `pageSize=200` 上限，并要求 `hasMore=false`；缺少水位线或仍有下一页都记录为 `BLOCKED`，不得用历史全量第一页缺少 docId 推断墓碑不存在。

变量模板中只保留空值和 `botUserId=10000`。真 token 不得进入 `http-client.env.json`、`env.example.sh`、`RESULTS.md` 或 Git diff。验收状态只能写 `PASS`、`FAIL`、`BLOCKED`；没有 Task 13 真运行证据时，Bot 章节保持 `BLOCKED`。
