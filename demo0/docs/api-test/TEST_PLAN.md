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
| `cases/` | 可选 curl / `.http` 用例（`01`–`02` 鉴权/造数/审核；`03-read`/`04-write` 读写；`05-admin`/`06-destructive` 治理/清理） |
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
