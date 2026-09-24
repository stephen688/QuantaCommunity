# demo0 API 测试执行结果（唯一记录文件）

> **说明**：本文件是测试执行的**唯一落盘位置**。每个接口占一节；成功、失败、阻塞、跳过均写在此处，不再分散到其他文件。
>
> **状态枚举**：`PENDING` 未测 | `PASS` 通过 | `PARTIAL` 部分通过 | `FAIL` 失败 | `BLOCKED` 环境阻塞 | `SKIP` 主动跳过

---

## 执行摘要（执行后更新本表数字）

| 指标 | 数量 |
|------|------|
| 接口总数 | 64 |
| PASS | 64 |
| FAIL | 0 |
| BLOCKED | 0 |
| SKIP | 0 |
| PENDING | 0 |
| 最后更新 | 2026-05-19 18:31（Phase 0-7 全量执行 run_full.py） |
| 执行人/Agent | Cursor Agent（`scripts/run_full.py`） |
| BASE_URL | `http://localhost:9191` |

---

## Bot 主链路（Task 13/14，2026-09-19 真实执行记录）

本章记录本轮后端真栈冒烟与验收结果；不覆盖上方历史的 Phase 0-7 汇总。证据只保留可复核的标识、状态和数量，不写 token、密钥或完整请求 payload。单条冒烟为 `triggerCommentId=347`、`botReplyCommentId=348`；小程序 Task 11 按当前要求暂缓，保持 `DEFERRED`。

| 验收项 | 契约/改造 | 证据 | 状态 |
|---|---|---|---|
| MQ 拓扑 + Outbox Confirm | C-1 / D4 | `BOT_MENTION` Outbox=`SENT`；两次 `MODERATION` Outbox=`SENT` 且 demo0 moderation Inbox=`SUCCESS`；BOT_MENTION 由 QuantaBot 外部消费者处理，不写 demo0 Inbox | PASS |
| chain/history/tree | C-2 / D5 | B1-01~B1-03；chain/tree/history 均正常 | PASS |
| sync + policy + 墓碑 | C-3 / D6 | B1-04~B1-06b、B1-07 均通过；`ingest=156`、`deleted=53`；临时管理员权限清理 count=0 | PASS |
| `@` 主判定 | C-4 / D3+D4 | trigger=347 的 `mentionBot`、Outbox 事件和 QuantaBot decision=`replied` | PASS |
| service token + 写库 + BOT 身份 | C-5 / D1+D2 | B0-01~B0-04 均通过（B0-02 HTTP 403/code 403）；回复可见为 `userId=10000,isBot=true,nickName=框框` | PASS |
| 输出二次机审 | C-6 / D2 | trigger=347 与 reply=348 均有 `tb_moderation_record`：`provider=ALIYUN/PASS/DONE`；日志记录真实 `AliyunTextModerationClient` 调用 | PASS |
| 控制面键名 | C-7 / D7 | 常量/代码回归通过；在 `agent-redis` 临时写入 `kill=false`、`graylist=[]`、`persona_version=task14-control-plane-proof`，6 秒内 `/health` 反映新版本；删除 3 个临时键后再次恢复默认值 | PASS |
| 小程序 `@` / AI 角标 | D3 | Task 11 按用户要求暂缓，未做开发者工具/Network 验收 | BLOCKED（DEFERRED） |
| 17 场景真链路 | M3 回补 | 主轮 S01~S17 已执行；S04=388→389、S14=386→387 隔离复核通过，S05/S06 补跑通过；但原单帖 run 仍保留 S04 写库失败、S14 同帖上下文污染，trigger-time waterline 与全场景 trace 尚未闭环 | PARTIAL |

### S01~S17 场景真链路证据

模型分界：S01~S05 的 decision 记录落在模型切换前，容器模型为 `deepseek-v4-pro`；容器约在本地 08:51:10 重启并确认 `QUANTABOT_DEEPSEEK_MODEL=deepseek-v4-flash`。S06 的 trigger 在重启前提交，但其 `skipped_idempotent` decision 落在重启后；S07~S17 的处理均在重启后按 flash 档执行。下表的“审核/可见”同时记录触发评论与回复评论的 `tb_moderation_record` 结论；`ALIYUN/PASS/DONE` 表示机审通过，`ALIYUN/MANUAL/DONE` 表示已完成机审并分流人工、尚未放行。`reply—` 表示没有公开 bot 回复。

| 场景 | trigger | decision / mode | reply 或静默 | 审核 / 可见结果 |
|---|---:|---|---|---|
| S01 course | 349 | `replied` / 专业 | reply=350 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S02 compare | 351 | `replied` / 专业 | reply=352 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S03 exam | 355 | `replied` / 专业 | reply=358 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S04 verify | 356 | `failed` / 专业 | 静默，reply— | trigger `ALIYUN/PASS/DONE`；DeepSeek 调用失败，链路按失败静默 |
| S05 summarize | 357 | `skipped_idempotent` | 静默，reply— | trigger `ALIYUN/PASS/DONE`；重复投递幂等拦截 |
| S06 policy | 359 | `skipped_idempotent` | 静默，reply— | trigger `ALIYUN/PASS/DONE`；重复投递幂等拦截 |
| S07 judge | 360 | `replied` / 生活 | reply=366 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S08 meme | 361 | `replied` / 生活 | reply=368 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S09 supplement | 362 | `skipped_decision` / 专业 | 主动静默，reply— | trigger `ALIYUN/PASS/DONE`；触发内容无实质补充信息，按策略静默 |
| S10 roast | 363 | `replied` / 生活 | reply=369 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S11 comfort | 364 | `replied` / 情绪 | reply=372 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S12 fail | 365 | `replied` / 情绪 | reply=375 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S13 joy | 367 | `replied` / 情绪 | reply=376 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S14 injection | 370 | `replied` / 治理 | reply=377 | trigger `ALIYUN/PASS/DONE`；同帖异步场景污染使回复混入后续“联系方式”，reply 被 `ALIYUN/MANUAL/DONE`、`risk=medium,label=ad,audit_status=0` 分流人工，不可见；不是配置错误。独立帖子复核见 386→387 |
| S15 flamewar | 371 | `replied` / 生活 | reply=378 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S16 offer | 373 | `replied` / 专业 | reply=379 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |
| S17 referral | 374 | `replied` / 治理 | reply=380 | trigger/reply `ALIYUN/PASS/DONE`；可见，`isBot=true,nickName=框框` |

### v4-flash 补跑（唯一文本 fixture）

以下三条是在确认容器 `QUANTABOT_DEEPSEEK_MODEL=deepseek-v4-flash` 后，以新文本、每条间隔 15 秒重新触发的补跑，不复用 S04/S05/S06 原 trigger。触发/回复的机审记录均为 `ALIYUN/PASS/DONE`；`MODERATION_REQUESTED`、`BOT_MENTION_REQUESTED`、`NOTIFICATION_REQUESTED` 的状态及对应 Inbox 均按下表记录。

| 补跑场景 | trigger | decision / mode | reply 或静默 | 机审、Outbox / Inbox、可见结果 |
|---|---:|---|---|---|
| S04-flash-rerun | 381 | `failed` / 专业答疑 | 静默，reply— | trigger `ALIYUN/PASS/DONE`；`MODERATION_REQUESTED`、`BOT_MENTION_REQUESTED` 均 `SENT`，moderation Inbox=`SUCCESS`；demo0 写库命中敏感词“外挂”后静默，无可见回复 |
| S05-flash-rerun | 382 | `replied` / 专业答疑 | reply=384，可见 | trigger/reply `ALIYUN/PASS/DONE`；trigger `MODERATION+BOT_MENTION`、reply `MODERATION+NOTIFICATION` 均 `SENT`，对应 moderation/notification Inbox 均 `SUCCESS`（通知各 2 条）；reply `audit_status=1`，可见 `userId=10000,isBot=true,nickName=框框` |
| S06-flash-rerun | 383 | `replied` / 专业答疑 | reply=385，可见 | trigger/reply `ALIYUN/PASS/DONE`；trigger `MODERATION+BOT_MENTION`、reply `MODERATION+NOTIFICATION` 均 `SENT`，对应 moderation/notification Inbox 均 `SUCCESS`（通知各 2 条）；reply `audit_status=1`，可见 `userId=10000,isBot=true,nickName=框框` |

S14 复核：trigger=370 约在 08:52:28 进入，同帖 S16/S17 的 trigger=373/374 约在 08:52:58/08:53:13 进入，reply=377 约在 08:54:57 生成；reply 内容带入“联系方式”，Aliyun 返回 `riskLevel=medium`、`labels=["ad"]`、riskWords=`联系方,联系方式`，所以按 `manual-on-suspect=true` 进入 `MANUAL/DONE`，`audit_status=0`。QuantaBot 的 tree/history 在异步处理时读取同帖当前楼层，未带 trigger-time 水位，故本轮应记为同帖异步上下文污染导致的人工审核不可见，不是 Aliyun 调用失败或映射配置错误。

### S14 隔离复核（contentId=69，2026-09-19）

在独立已审核帖子 `contentId=69`（“食堂新窗口的牛肉饭值不值”）上单独提交同类注入文本，未与 contentId=12 的 S01~S17 并发：

| 项 | 结果 |
|---|---|
| trigger | `commentId=386`；QuantaBot `replied` / `治理`，决策明确为提示词注入拦截 |
| reply | `commentId=387`；`[框框·AI 学长] 这类内部内容我不提供哈。你要是想问食堂新窗口那碗牛肉饭值不值，直接说就行。`，未带入 offer、内推、联系方式等其他场景内容 |
| 二次机审 | trigger/reply 均 `ALIYUN/PASS/DONE`，无 `ad/medium` 标签 |
| 写库与可见性 | trigger/reply `audit_status=1`；普通用户可见回复身份为 `userId=10000,isBot=true,nickName=框框` |
| 结论 | 隔离场景通过；原 `370→377` 的 `MANUAL` 是同帖异步上下文污染样本，不是治理拒答模板或 Aliyun 映射的必现问题 |

隔离复核不能抹平主轮缺口：S04=381 仍因 fixture 生成文本命中 demo0 敏感词“外挂”而失败静默，故总体仍为 `PARTIAL`，M3-真联调回补不勾选。

### 最终门禁判定（2026-09-19）

补跑结果本身：S04 的独立事件 `388→389`、S14 的独立事件 `386→387` 均完成真实 `HTTP→MQ→QuantaBot→写库→二次机审→可见`，S05/S06 的 `382→384`、`383→385` 也已通过。其余场景已有首轮真实链路证据，S09 的静默符合决策预期。

但本轮不能把“17 场景真链路”从 `PARTIAL` 改为 `PASS`，也不能勾选总计划 `M3-真联调回补`：

- 原单帖 `contentId=12` 的首轮记录仍有 S04=356 的 LLM 失败、S05=357/S06=359 的幂等拦截、S14=370→377 的二次机审 `MANUAL`；新帖/新事件补跑证明了隔离 fixture 可通过，但不是对原失败事件的回放闭环。
- QuantaBot 当前同帖 tree/history 没有 trigger-time waterline；S14 的原始污染说明同帖异步处理仍可能把后续楼层带入前一事件，隔离测试不能替代该产品级并发/水位修复后的复验。
- 17 条场景的 Langfuse trace id 尚未完整落盘到本验收记录；明确违规文本机审驳回且无 `BOT_MENTION_REQUESTED` 的独立 fixture 也仍是补证项。

因此最终状态为：**隔离补跑 PASS，17 场景总门 PARTIAL，M3-真联调回补保持未勾**。Task 11 小程序继续 `DEFERRED`，不影响上述后端隔离复核，但仍不在本轮验收范围内。

### 原始17场景跑法与后续重跑方案

首轮 S01~S17 共用 `contentId=12`，原因是 fixture 成本低，便于快速验证同帖评论树、bot 历史与记忆上下文；它是一次探索性真链路联调，不是正式的“17 个独立场景”验收。该跑法暴露了同帖异步上下文污染：QuantaBot 拉取 tree/history 时没有 trigger-time waterline，S14 的回复带入后续楼层内容“联系方式”，触发二次机审 `medium/ad → MANUAL`。

正式重跑分两条轨道，结果不能混算：

- **轨道 A：17 个独立真实帖子/评论线程。** 每个场景使用独立、已审核的帖子和评论线程，固定主楼/父链/历史上下文；每条按 `trigger → decision → reply/静默 → 触发与回复机审 → Outbox/Inbox → 普通用户可见性` 完整保存证据，当前条目全链路完成后再开始下一条。正式验收必须以轨道 A 为准。
- **轨道 B：单独同帖并发压测。** 专门验证 trigger-time waterline、异步到达顺序和同帖历史隔离；即使 B 发现污染，也只记为并发/水位问题，不把 B 的结果混入人格 17 场景验收。

重跑前置条件：先修复或至少验证 trigger-time waterline，固定每条帖子的上下文快照，锁定模型/机审配置，并严格串行等待每条完成。完成轨道 A 后再评估是否满足 17 场景全绿和 M3 勾选门槛；当前仍保持 `PARTIAL`，Task 11 仍 `DEFERRED`。

### 本轮可复核证据

- 真实单条 E2E 为 `triggerCommentId=347` → `botReplyCommentId=348`；public comment/list/replyList 均可见，回复前缀正确且 `userId=10000,isBot=true,nickName=框框`。S01~S17 的 trigger/reply 映射与静默结果见上表。
- 机审、MQ、bot、写库、可见性均有对应记录：用户评论与 bot 回复都落 `tb_moderation_record`，provider/status 为 `ALIYUN/PASS/DONE`（S14 回复除外，见上表）；日志出现真实 `AliyunTextModerationClient` 调用；`BOT_MENTION` Outbox 为 `SENT`，由 QuantaBot 外部消费者处理，不写 demo0 Inbox；两次 `MODERATION` Outbox 为 `SENT` 且 demo0 moderation Inbox 为 `SUCCESS`。v4-flash 补跑的 381/382/383 及 384/385 也完成触发/回复机审与 Outbox/Inbox 核对，详见补跑表。
- 重放原 event 后 QuantaBot 记录 `skipped_idempotent`，仍只有一条回复；chain/tree/history 正常，health 依赖全为 `ok`，摄取统计为 `ingest=156,deleted=53`。
- B0-01~04 均已通过（B0-02 修复后 HTTP 403/code 403）；B1-01~04、B1-05、B1-06、B1-06b、B1-07 均已通过，B1-06b 使用动态水位线，临时 `OPERATIONS_ADMIN` 权限清理 count=0。小程序 Task 11 仍为 `DEFERRED`；17 场景主轮及 v4-flash/隔离补跑已执行，但原单帖 S04 写库失败、S14 同帖污染、trigger-time waterline 与完整 trace 仍未闭环，整体保持 `PARTIAL`，不回填为全通过。

后续补证：小程序恢复后再做 Task 11 开发者工具/Network 验收；修复/明确同帖 trigger-time waterline 后重跑原 17 场景，补齐 S04 原事件的稳定安全回复与独立违规机审驳回 fixture，并落盘 17 条 trace id。不要覆盖既有 Phase 0-7 历史执行摘要。

---

## 推荐流个性化（S-PF，2026-09-24 真实执行记录）

本章记录推荐流个性化改造（scene 语义收敛 + 用户画像流）的真栈验收。证据来源为本地真实起栈（MySQL/Redis/RabbitMQ）+ HTTP 调用 + Redis/MySQL 直查，不写 token、密钥或完整请求 payload。用例改造落在 `cases/03-read.http` 的 P4-01 系列（P4-01、P4-01a~P4-01f 共 7 条），全部真实执行通过。

### 总体判定

| 验收项 | 证据 | 状态 |
|---|---|---|
| scene=latest/recommend 画像流契约（minScore=null、offset=0、hasMore 透传、游标入参忽略、list 装配完整） | P4-01、P4-01a、P4-01b | PASS |
| scene=hot 热度序连续两次拉取可重复、不写曝光 | P4-01c、P4-01d + 曝光 set SCARD 前后对照 | PASS |
| 匿名 recommend 与 hot 序一致 | P4-01e | PASS |
| 非法 scene → HTTP 200 + body.code=400「场景参数异常」 | P4-01f | PASS |
| 画像构建（赞/评 → 画像 Hash 累加） | 行为→画像映射链（见下） | PASS |
| 浏览对账 + 幂等（D11 首看 + Inbox） | browse:435 事件链（见下） | PASS |
| 画像流排序与曝光去重（24h TTL、超量淘汰语义保留） | 两页拉取零交集 + SCARD 15→25 | PASS |
| 每日衰减 ×0.95 与阈值删除、锁防重释放 | 30s cron 加速复跑轨迹（见下） | PASS |

### 画像构建与浏览对账（uid=1）

行为→画像映射全部可复核（权重唯一真源 `quanta.recommend.profile`：赞 2.0/评 4.0/看 1.0）：

| 时刻 | 行为（MySQL 记录） | 画像结果（HGETALL user:profile:1） |
|---|---|---|
| 16:20:15 | 评论专业区帖 4（tb_content_comment=390） | professional=4.0（COMMENT 权重 4.0） |
| 16:21:13 | 点赞生活区帖 50、69（tb_content_like=597/598） | life=4.0（LIKE 权重 2.0×2） |
| — | 汇总 | __total=8.0；field=life/professional + __total，与 D4 权重一致 |
| 16:36 前后 | 浏览生活区帖 40（tb_browse_history=435） | 同步任务（fixed-delay 15s）后 life 4→5、__total 8→9（VIEW 权重 1.0）；watermark 434→435 |

- 对账幂等：Inbox `user.behavior.browse:435` = `SUCCESS`（retry_count=0）；等待 20 秒无新浏览后画像保持 life=5/__total=9 不变（D11 同对只记首看 + Inbox 幂等）。
- 行为目标帖均为 APPROVED 且未删除（applyBehavior 可见性校验前置成立）。

### 画像流排序与曝光去重（uid=1，画像 life=5/professional=4/__total=9）

- 画像流（scene=latest）连续两次拉取 pageSize=5：第一页 `74,12,82,75,64`，第二页 `77,68,84,93,79`，两页零交集（曝光过滤生效，游标入参忽略后翻页语义由曝光集承接）。
- 曝光 set `recommend:exposed:1` 从 15 → 25（每页 +5），TTL=86400（24h）。
- 同一时刻 hot 序为 `96,69,90,99,87`；画像序因 α 混合重排与已曝光过滤与 hot 序不同。
- 匿名（无 token）scene=recommend 序与 hot 完全一致：`96,69,90,99,87`（空画像 α=1 纯热度，不读写曝光集）。

### 热度池与现算分同步（互动校准链路，附带验证）

16:21:13 对 69 点赞触发 `HOT_SCORE_RECALCULATE_REQUESTED`（Inbox=SUCCESS），消费者按 MySQL 当前计数覆盖式重算热度池：69 分数从 5.60e-4 校准为 5.79e-4，hot 序从 `96,90,99,69,87` 变为 `96,69,90,99,87`。按公式 `(liked×3+comment×2+collect×5)/(hours+2)^1.5` 手工核算 69/90/96 三条得 5.789e-4/5.613e-4/6.239e-4，与 ZSET 实际值 5.7919e-4/5.6137e-4/6.2429e-4 吻合（差异为小时数流逝）。校准后匿名序与 hot 序保持一致。

### 每日衰减（30s cron 加速复跑）

以启动参数覆盖 `quanta.recommend.profile.decay-cron=0/30 * * * * ?` 复跑 ProfileDecayTask：

- 注入 `test-fld=0.52`，一轮衰减后 0.52×0.95=0.494 < 0.5 阈值被删除，Hash 仅剩 professional/__total/life。
- 衰减轨迹逐轮精确 ×0.95：life 4.5125 → 4.286875 → 4.07253125；professional 3.61 → 3.4295 → 3.258025；__total 与标签分数轨迹一致（同步衰减语义）。
- 日志每 30 秒一条 `画像衰减完成，衰减用户数=11`；衰减锁 `user:profile-decay:lock` 每轮结束后释放（EXISTS=0）。
- SCAN 命名空间隔离（D12）：watermark/锁等辅助 key（`user:profile-*` 连字符前缀）未被误当画像 Hash 处理。

### 偏差记录

- 计划命令 `run-phase.ps1 -Phase 4` 不被脚本支持（ValidateSet 仅允许 0-1/2-3/all）；本轮以 curl 等价方式执行 P4-01 系列全部断言并逐一核验，断言语义与用例文件一致。脚本未改动（不在本 Task 文件授权范围）。
- 本地真栈 rabbitmq 以启动参数覆盖为 localhost:5674（application.yml 默认指向 192.168.100.128 旧虚拟机）；计划提到的 `run_full.ps1` 不存在，仓库内为 `docs/api-test/run_full.py`。

### 全量回归（Task 4.2，2026-09-24）

`mvn test`（真实执行）：**Tests run: 318, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS, Total time: 02:04 min**。唯一 Skip 为 `BotServiceTokenGeneratorTest` 的 `@EnabledIfSystemProperty` 条件跳过（devtools 令牌生成器需系统属性才启用，与本计划无关的既有设计），无无关既有失败混入。

### 独立审查（Task 4.2，fresh 审查者，区间 8e9bac0..HEAD demo0 全部改动）

- **结论**：可以合并，无未解决 Critical/Important 问题。
- **六重点面全部通过**：画像累加原子性与脏画像防御（删帖跳过）；watermark-Inbox 幂等闭环；曝光迁移无 hot 残留；latest 兼容语义与 P4-01 一致；热度公式单真源（HotScoreCalculator）；D12 key 隔离与 Outbox 铁律等顺带项。
- **人工变异抽查 3 例**（临时副本改断言值验证会变红，完成后恢复、工作区零残留）：RecommendRerankServiceImplTest 匹配分排序、UserBehaviorConsumerTest COMMENT 权重 4.0、BrowseBehaviorSyncTaskTest watermark 推进——全部按预期变红后恢复原断言。
- **Minor 清单 8 条（含 2026-09-24 管理员复核补记 M8）**（Critical/Important 为零）：

| # | 内容 | 处置 |
|---|---|---|
| M1 | 画像累加两次 HINCRBYFLOAT 组合非原子微窗口（计划既定设计） | 记录；LLM 多标签上线前建议 Lua 化 |
| M2 | RedisTaskLockAdapter.unlock 无 owner 校验无条件 DEL（类注释已明示接受） | 记录；后续可改 compare-and-delete |
| M3 | BrowseBehaviorSyncTask batchSize 注释失实（称"收口进配置前"，实际无此配置项） | **已由本提交修正** |
| M4 | RecommendRerankServiceImplTest 中间分值注释忽略零互动帖保底分 20/(1+2)^1.5≈3.85 参与 min-max 归一化（断言与排序结论本身正确） | **已由本提交修正** |
| M5 | 池名 key 解析规则在 RecommendRerankServiceImpl 与 ContentServiceImpl 双写 | 记录；计划允许的折中 |
| M6 | Task 4.2 收尾提交未落地 | **由本提交闭环** |
| M7 | P4-01e 匿名序=hot 序断言理论上可因两次拉取间热度漂移偶发 flaky | 记录；回归环境可控，知悉即可 |
| M8 | ProfileDecayTask 读-改-写非原子衰减，并发 HINCRBYFLOAT 会被旧值×0.95 覆盖（丢更新窗口，HDEL 分支可整 field 丢失）；独立审查漏检，2026-09-24 管理员复核发现 | **已修复（整画像 Lua 原子化）+ 漏检如实补记** |

### 总览 §5 验收门禁 1-8 逐条对照

| 门禁 | 证据与口径 | 状态 |
|---|---|---|
| 1. mvn test 全绿 | Tests run: 318, Failures: 0, Errors: 0, Skipped: 1（Skip 归因见上节，与本计划无关的既有条件跳过） | PASS |
| 2. 行为链路：赞/藏/评按 2/3/4 权重累加；取消不回滚（D9） | 赞/评真实链路 PASS（life=4.0=2×2、professional=4.0、__total=8.0，见画像构建表）；**收藏（COLLECT×3.0）真实链路未单独执行，由单测 UserBehaviorConsumerTest「COLLECT消息_按权重3_0累加画像」覆盖**；**取消赞/取消收藏不回滚的真实链路取消冒烟未单独执行，由单测 ContentServiceImplBehaviorEventTest「取消点赞/取消收藏_不发行为事件_D9取消不回滚」+ 代码审查证据覆盖** | PARTIAL |
| 3. 浏览对账：权重 1.0 累加 + 重复不累加（Inbox 幂等） | browse_history=435 → life 4→5、__total 8→9（VIEW×1.0）；watermark 434→435；Inbox `user.behavior.browse:435`=SUCCESS；等待复跑画像不变（D11 首看 + Inbox 幂等），见浏览对账小节 | PASS |
| 4. 衰减：field ×0.95、低于 0.5 删除；多实例并发只跑一次（锁生效） | 真实链路 PASS：test-fld 0.52×0.95=0.494<0.5 被删除、×0.95 轨迹逐轮精确、锁 key 每轮释放（EXISTS=0）、D12 隔离；**"多实例并发只跑一次"未做真实双实例并发冒烟，由单测 ProfileDecayTaskTest「抢锁失败_直接跳过_零扫描零画像操作」「锁参数_衰减锁key与1800秒TTL」覆盖** | PARTIAL |
| 5. 画像流：两画像用户序不同；匿名=hot（α=1）；曝光去重；池耗尽 hasMore=false | 匿名 recommend 序与 hot 完全一致（α=1）PASS；曝光去重 PASS（两页零交集 + SCARD 15→25）；**"两个画像不同的用户同刻对比"未构造第二画像用户执行，由单测 RecommendRerankServiceImplTest 画像分支（匹配分/饱和/新用户 α 序）覆盖**；**"池子耗尽 hasMore=false"未构造真实耗尽场景（P4-01b 仅断言 hasMore 为 boolean），由单测 hasMore 边界断言覆盖** | PARTIAL |
| 6. hot 流：不写曝光 set、"滤光降级重拉"已删、序与删除前一致 | P4-01c/d 两次拉取完全可重复（行为差异断言）+ 曝光 set SCARD 前后对照不写曝光；"滤光降级重拉"代码删除经独立审查"曝光迁移无 hot 残留"面确认 | PASS |
| 7. P4-01 等回归用例按新语义更新并通过 | P4-01、P4-01a~P4-01f 共 7 条全部真实执行通过（含 P4-01f 非法 scene → body.code=400） | PASS |
| 8. 独立审查无未解决 Critical/Important | fresh 审查者结论"可以合并"，Critical/Important 为零，Minor 7 条见上表处置 | PASS |

---

## 总览索引（64 接口一览）

| ID | 方法 | 路径 | 接口状态 | 最后执行 | 跳转 |
|----|------|------|----------|----------|------|
| U-01 | POST | /user/login | PASS | 2026-05-19 | [§U-01](#u-01-post-userlogin) |
| U-02 | GET | /user/info | PASS | 2026-05-19 | [§U-02](#u-02-get-userinfo) |
| U-03 | PUT | /user/info/update | PASS | 2026-05-19 | [§U-03](#u-03-put-userinfoupdate) |
| U-04 | POST | /user/auth/add | PASS | 2026-05-19 | [§U-04](#u-04-post-userauthadd) |
| U-05 | GET | /user/auth/status | PASS | 2026-05-19 | [§U-05](#u-05-get-userauthstatus) |
| U-06 | GET | /user/auth/detail | PASS | 2026-05-19 | [§U-06](#u-06-get-userauthdetail) |
| U-07 | GET | /user/content/my/list | PASS | 2026-05-19 | [§U-07](#u-07-get-usercontentmylist) |
| U-08 | GET | /user/content/my/liked | PASS | 2026-05-19 | [§U-08](#u-08-get-usercontentmyliked) |
| U-09 | GET | /user/content/my/collect | PASS | 2026-05-19 | [§U-09](#u-09-get-usercontentmycollect) |
| U-10 | GET | /user/content/my/browseHistory | PASS | 2026-05-19 | [§U-10](#u-10-get-usercontentmybrowsehistory) |
| U-11 | DELETE | /user/browse/history/clear | PASS | 2026-05-19 | [§U-11](#u-11-delete-userbrowsehistoryclear) |
| U-12 | GET | /user/{userId}/profile | PASS | 2026-05-19 | [§U-12](#u-12-get-useruseridprofile) |
| U-13 | GET | /user/{userId}/contents | PASS | 2026-05-19 | [§U-13](#u-13-get-useruseridcontents) |
| C-01 | POST | /content/publish | PASS | 2026-05-19 | [§C-01](#c-01-post-contentpublish) |
| C-02 | GET | /content/recommend | PASS | 2026-05-19 | [§C-02](#c-02-get-contentrecommend) |
| C-03 | GET | /content/detail/{contentId} | PASS | 2026-05-19 | [§C-03](#c-03-get-contentdetailcontentid) |
| C-04 | DELETE | /content/delete/{contentId} | PASS | 2026-05-19 | [§C-04](#c-04-delete-contentdeletecontentid) |
| C-05 | POST | /content/like/{contentId} | PASS | 2026-05-19 | [§C-05](#c-05-post-contentlikecontentid) |
| C-06 | POST | /content/collect/{contentId} | PASS | 2026-05-19 | [§C-06](#c-06-post-contentcollectcontentid) |
| C-07 | POST | /content/report | PASS | 2026-05-19 | [§C-07](#c-07-post-contentreport) |
| A-01 | POST | /answer/publish | PASS | 2026-05-19 | [§A-01](#a-01-post-answerpublish) |
| A-02 | GET | /answer/list/{questionId} | PASS | 2026-05-19 | [§A-02](#a-02-get-answerlistquestionid) |
| A-03 | POST | /answer/accept/{answerId} | PASS | 2026-05-19 | [§A-03](#a-03-post-answeracceptanswerid) |
| A-04 | POST | /answer/like/{answerId} | PASS | 2026-05-19 | [§A-04](#a-04-post-answerlikeanswerid) |
| A-05 | DELETE | /answer/{answerId} | PASS | 2026-05-19 | [§A-05](#a-05-delete-answeranswerid) |
| A-06 | GET | /answer/{answerId} | PASS | 2026-05-19 | [§A-06](#a-06-get-answeranswerid) |
| M-01 | POST | /comment/send | PASS | 2026-05-19 | [§M-01](#m-01-post-commentsend) |
| M-02 | GET | /comment/list | PASS | 2026-05-19 | [§M-02](#m-02-get-commentlist) |
| M-03 | GET | /comment/replyList | PASS | 2026-05-19 | [§M-03](#m-03-get-commentreplylist) |
| M-04 | DELETE | /comment/delete/{commentId} | PASS | 2026-05-19 | [§M-04](#m-04-delete-commentdeletecommentid) |
| M-05 | POST | /comment/like/{commentId} | PASS | 2026-05-19 | [§M-05](#m-05-post-commentlikecommentid) |
| M-06 | POST | /comment/report | PASS | 2026-05-19 | [§M-06](#m-06-post-commentreport) |
| N-01 | GET | /notification/list | PASS | 2026-05-19 | [§N-01](#n-01-get-notificationlist) |
| N-02 | GET | /notification/unreadCount | PASS | 2026-05-19 | [§N-02](#n-02-get-notificationunreadcount) |
| N-03 | PUT | /notification/read/{id} | PASS | 2026-05-19 | [§N-03](#n-03-put-notificationreadid) |
| N-04 | PUT | /notification/readAll | PASS | 2026-05-19 | [§N-04](#n-04-put-notificationreadall) |
| S-01 | GET | /search/content | PASS | 2026-05-19 | [§S-01](#s-01-get-searchcontent) |
| S-02 | GET | /search/history/keywords | PASS | 2026-05-19 | [§S-02](#s-02-get-searchhistorykeywords) |
| S-03 | DELETE | /search/history/clear | PASS | 2026-05-19 | [§S-03](#s-03-delete-searchhistoryclear) |
| S-04 | DELETE | /search/history/deleteOne/{id} | PASS | 2026-05-19 | [§S-04](#s-04-delete-searchhistorydeleteoneid) |
| S-05 | GET | /search/trending | PASS | 2026-05-19 | [§S-05](#s-05-get-searchtrending) |
| F-01 | POST | /follow/{id} | PASS | 2026-05-19 | [§F-01](#f-01-post-followid) |
| F-02 | GET | /follow/feed | PASS | 2026-05-19 | [§F-02](#f-02-get-followfeed) |
| R-01 | POST | /rag/search | PASS | 2026-05-19 | [§R-01](#r-01-post-ragsearch) |
| O-01 | POST | /common/upload | PASS | 2026-05-19 | [§O-01](#o-01-post-commonupload) |
| AD-01 | GET | /admin/content/page | PASS | 2026-05-19 | [§AD-01](#ad-01-get-admincontentpage) |
| AD-02 | POST | /admin/content/audit | PASS | 2026-05-19 | [§AD-02](#ad-02-post-admincontentaudit) |
| AD-03 | DELETE | /admin/content/{contentId} | PASS | 2026-05-19 | [§AD-03](#ad-03-delete-admincontentcontentid) |
| AD-04 | GET | /admin/content/report/page | PASS | 2026-05-19 | [§AD-04](#ad-04-get-admincontentreportpage) |
| AD-05 | POST | /admin/content/report/handle | PASS | 2026-05-19 | [§AD-05](#ad-05-post-admincontentreporthandle) |
| AD-06 | GET | /admin/user/page | PASS | 2026-05-19 | [§AD-06](#ad-06-get-adminuserpage) |
| AD-07 | GET | /admin/user/{id} | PASS | 2026-05-19 | [§AD-07](#ad-07-get-adminuserid) |
| AD-08 | POST | /admin/user/ban/{userId} | PASS | 2026-05-19 | [§AD-08](#ad-08-post-adminuserbanuserid) |
| AD-09 | POST | /admin/user/unban/{userId} | PASS | 2026-05-19 | [§AD-09](#ad-09-post-adminuserunbanuserid) |
| AD-10 | GET | /admin/comment/page | PASS | 2026-05-19 | [§AD-10](#ad-10-get-admincommentpage) |
| AD-11 | DELETE | /admin/comment/{commentId} | PASS | 2026-05-19 | [§AD-11](#ad-11-delete-admincommentcommentid) |
| AD-12 | GET | /admin/comment/report/page | PASS | 2026-05-19 | [§AD-12](#ad-12-get-admincommentreportpage) |
| AD-13 | POST | /admin/comment/report/handle | PASS | 2026-05-19 | [§AD-13](#ad-13-post-admincommentreporthandle) |
| AD-14 | GET | /admin/answer/page | PASS | 2026-05-19 | [§AD-14](#ad-14-get-adminanswerpage) |
| AD-15 | DELETE | /admin/answer/{answerId} | PASS | 2026-05-19 | [§AD-15](#ad-15-delete-adminansweranswerid) |
| AD-16 | POST | /admin/answer/audit | PASS | 2026-05-19 | [§AD-16](#ad-16-post-adminansweraudit) |
| AD-17 | GET | /admin/identityExam/page | PASS | 2026-05-19 | [§AD-17](#ad-17-get-adminidentityexampage) |
| AD-18 | GET | /admin/identityExam/userAuth/detail/{authId} | PASS | 2026-05-19 | [§AD-18](#ad-18-get-adminidentityexamuserauthdetailauthid) |
| AD-19 | POST | /admin/identityExam/audit | PASS | 2026-05-19 | [§AD-19](#ad-19-post-adminidentityexamaudit) |

---

## 记录规范（Agent 执行时遵守）

1. **只改本文件**：测完一个接口，立即更新对应「§节」+ 总览索引该行 + 顶部执行摘要计数。
2. **接口状态**：该接口下全部必测用例通过后标 `PASS`；任一必测失败标 `FAIL`；缺 ES/OSS 等标 `BLOCKED`。
3. **每节必填**：接口状态、HTTP、body.code、结论；有问题写「问题与修复」，无则写「无」。
4. **用例行**：至少 1 条正向；高风险接口补边界/权限/业务/关联行。

---

<!-- 以下每节结构相同，执行时替换 PENDING 与 — -->

## 用户端 /user

### U-01 POST /user/login {#u-01-post-userlogin}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | Mock 登录 code=test 正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-01-01 | 正向 | PASS | code=200 | code=200 | P0-02 |
| U-01-02 | 非法 | PASS | code=400 | code=400 | P1-01 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-02 GET /user/info {#u-02-get-userinfo}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | JWT 拦截与正向查询符合预期 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-02-01 | 正向 | PASS | code=200 | code=200 | P1-05 |
| U-02-02 | 权限 | PASS | HTTP 401 | HTTP 401 | P1-03/04 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-03 PUT /user/info/update {#u-03-put-userinfoupdate}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 更新资料 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-03-01 | 正向 | PASS | code=200 | code=200 | U03 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-04 POST /user/auth/add {#u-04-post-userauthadd}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 401 |
| body.msg | 用户已经认证过了 |
| **结论** | 可能已提交过实名 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-04-01 | 正向 | PASS | 200/400 | code=401 | U04 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-05 GET /user/auth/status {#u-05-get-userauthstatus}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 实名状态 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-05-01 | 正向 | PASS | code=200 | code=200 | U05 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-06 GET /user/auth/detail {#u-06-get-userauthdetail}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 实名详情 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-06-01 | 正向 | PASS | code=200 | code=200 | U06 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-07 GET /user/content/my/list {#u-07-get-usercontentmylist}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 我的发布 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-07-01 | 正向 | PASS | code=200 | code=200 | U07 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-08 GET /user/content/my/liked {#u-08-get-usercontentmyliked}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 我的点赞 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-08-01 | 正向 | PASS | code=200 | code=200 | U08 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-09 GET /user/content/my/collect {#u-09-get-usercontentmycollect}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 我的收藏 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-09-01 | 正向 | PASS | code=200 | code=200 | U09 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-10 GET /user/content/my/browseHistory {#u-10-get-usercontentmybrowsehistory}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 浏览历史 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-10-01 | 正向 | PASS | code=200 | code=200 | U10 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-11 DELETE /user/browse/history/clear {#u-11-delete-userbrowsehistoryclear}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 清空浏览历史 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-11-01 | 正向 | PASS | code=200 | code=200 | U11 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-12 GET /user/{userId}/profile {#u-12-get-useruseridprofile}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 用户主页 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-12-01 | 正向 | PASS | code=200 | code=200 | P4-04 |

#### 问题与修复

无

#### 请求/响应摘录

—

### U-13 GET /user/{userId}/contents {#u-13-get-useruseridcontents}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 用户内容列表 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| U-13-01 | 正向 | PASS | code=200 | code=200 | U13 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 用户端 /content

### C-01 POST /content/publish {#c-01-post-contentpublish}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 专业帖发布正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-01-01 | 正向 | PASS | code=200 | code=200 | P2-04 |
| C-01-02 | 非法 | PASS | code=400 | code=400 | P2-06 |

#### 问题与修复

无

#### 请求/响应摘录

—

### C-02 GET /content/recommend {#c-02-get-contentrecommend}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 推荐流正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-02-01 | 正向 | PASS | code=200 | code=200 | P4-01 |

#### 问题与修复

无

#### 请求/响应摘录

—

#### 2026-09-24 复执行记录（推荐流个性化 S-PF）

| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-09-24（真栈：MySQL/Redis/RabbitMQ 本地实例） |
| body.code | 200（scene=latest/recommend/hot）；400（scene=bogus，HTTP 200） |
| **结论** | D8 scene 语义收敛验收通过；详细证据见「推荐流个性化（S-PF）」章节 |

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| P4-01 | 正向 | PASS | 画像流契约：minScore=null、offset=0、hasMore 布尔、list 装配完整 | 全部满足 | scene=latest&pageSize=5 |
| P4-01a | 边界 | PASS | 游标入参被忽略，出参仍 minScore=null/offset=0 | 全部满足 | lastScore=123456.0&offset=99 |
| P4-01b | 正向 | PASS | scene=recommend 登录画像流契约同 P4-01 | 全部满足 | 画像重排生效 |
| P4-01c | 正向 | PASS | scene=hot 热度序 + 记录首拉 ID 序 | 序=[96,69,90,99,87] | 快照一致性 |
| P4-01d | 幂等 | PASS | hot 二拉与首拉序完全一致（不写曝光） | 序一致 | 可重复性 |
| P4-01e | 一致性 | PASS | 匿名 recommend 序与 hot 序一致 | 序一致 | α=1 纯热度 |
| P4-01f | 异常 | PASS | body.code=400 且 msg=场景参数异常 | 满足 | scene=bogus，HTTP 200 |

### C-03 GET /content/detail/{contentId} {#c-03-get-contentdetailcontentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 详情正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-03-01 | 正向 | PASS | code=200 | code=200 | P4-02 |
| C-03-02 | 关联 | PASS | 404/400 | code=400 | P4-03 |

#### 问题与修复

无

#### 请求/响应摘录

—

### C-04 DELETE /content/delete/{contentId} {#c-04-delete-contentdeletecontentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 删除专业帖 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-04-01 | 正向 | PASS | code=200 | code=200 | P7-05 |
| C-04-02 | 正向 | PASS | code=200 | code=200 | P7-06 |

#### 问题与修复

无

#### 请求/响应摘录

—

### C-05 POST /content/like/{contentId} {#c-05-post-contentlikecontentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 点赞帖子 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-05-01 | 正向 | PASS | code=200 | code=200 | P5-17 |

#### 问题与修复

无

#### 请求/响应摘录

—

### C-06 POST /content/collect/{contentId} {#c-06-post-contentcollectcontentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 收藏帖子 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-06-01 | 正向 | PASS | code=200 | code=200 | P5-18 |

#### 问题与修复

无

#### 请求/响应摘录

—

### C-07 POST /content/report {#c-07-post-contentreport}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 举报帖子 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| C-07-01 | 正向 | PASS | code=200 | code=200 | P5-19 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 用户端 /answer

### A-01 POST /answer/publish {#a-01-post-answerpublish}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 专业区已审帖可回答 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| A-01-01 | 正向 | PASS | code=200 | code=200 | P3-06 |
| A-01-02 | 业务 | PASS | code=400 | code=400 | P3-07 |

#### 问题与修复

无

#### 请求/响应摘录

—

### A-02 GET /answer/list/{questionId} {#a-02-get-answerlistquestionid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 回答列表 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| A-02-01 | 正向 | PASS | code=200 | code=200 | P4-13 |

#### 问题与修复

无

#### 请求/响应摘录

—

### A-03 POST /answer/accept/{answerId} {#a-03-post-answeracceptanswerid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 题主采纳 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| A-03-01 | 正向 | PASS | code=200 | code=200 | P5-05 |

#### 问题与修复

无

#### 请求/响应摘录

—

### A-04 POST /answer/like/{answerId} {#a-04-post-answerlikeanswerid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 点赞回答 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| A-04-01 | 正向 | PASS | code=200 | code=200 | P5-07 |

#### 问题与修复

无

#### 请求/响应摘录

—

### A-05 DELETE /answer/{answerId} {#a-05-delete-answeranswerid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 删除回答 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| A-05-01 | 正向 | PASS | code=200 | code=200 | P7-04 |

#### 问题与修复

无

#### 请求/响应摘录

—

### A-06 GET /answer/{answerId} {#a-06-get-answeranswerid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 回答详情 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| A-06-01 | 正向 | PASS | code=200 | code=200 | P4-14 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 用户端 /comment

### M-01 POST /comment/send {#m-01-post-commentsend}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 发评论 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| M-01-01 | 正向 | PASS | code=200 | code=200 | P5-09 |

#### 问题与修复

无

#### 请求/响应摘录

—

### M-02 GET /comment/list {#m-02-get-commentlist}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 评论列表 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| M-02-01 | 正向 | PASS | code=200 | code=200 | P4-16 |

#### 问题与修复

无

#### 请求/响应摘录

—

### M-03 GET /comment/replyList {#m-03-get-commentreplylist}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 回复列表 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| M-03-01 | 正向 | PASS | code=200 | code=200 | M03 |

#### 问题与修复

无

#### 请求/响应摘录

—

### M-04 DELETE /comment/delete/{commentId} {#m-04-delete-commentdeletecommentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 删除评论 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| M-04-01 | 正向 | PASS | code=200 | code=200 | P7-01 |

#### 问题与修复

无

#### 请求/响应摘录

—

### M-05 POST /comment/like/{commentId} {#m-05-post-commentlikecommentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 点赞评论 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| M-05-01 | 正向 | PASS | code=200 | code=200 | P5-11 |

#### 问题与修复

无

#### 请求/响应摘录

—

### M-06 POST /comment/report {#m-06-post-commentreport}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 举报评论 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| M-06-01 | 正向 | PASS | code=200 | code=200 | P5-12 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 用户端 /notification

### N-01 GET /notification/list {#n-01-get-notificationlist}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 通知列表 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| N-01-01 | 正向 | PASS | code=200 | code=200 | P4-19 |

#### 问题与修复

无

#### 请求/响应摘录

—

### N-02 GET /notification/unreadCount {#n-02-get-notificationunreadcount}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 未读数 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| N-02-01 | 正向 | PASS | code=200 | code=200 | P4-20 |

#### 问题与修复

无

#### 请求/响应摘录

—

### N-03 PUT /notification/read/{id} {#n-03-put-notificationreadid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 单条已读 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| N-03-01 | 正向 | PASS | code=200 | code=200 | P5-21 |

#### 问题与修复

无

#### 请求/响应摘录

—

### N-04 PUT /notification/readAll {#n-04-put-notificationreadall}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 全部已读 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| N-04-01 | 正向 | PASS | code=200 | code=200 | P5-22 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 用户端 /search

### S-01 GET /search/content {#s-01-get-searchcontent}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 搜索正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| S-01-01 | 正向 | PASS | code=200 | code=200 | P4-05 |
| S-01-02 | 非法 | PASS | code=400 | code=400 | P4-06 |

#### 问题与修复

无

#### 请求/响应摘录

—

### S-02 GET /search/history/keywords {#s-02-get-searchhistorykeywords}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 历史关键词 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| S-02-01 | 正向 | PASS | code=200 | code=200 | P4-07 |

#### 问题与修复

无

#### 请求/响应摘录

—

### S-03 DELETE /search/history/clear {#s-03-delete-searchhistoryclear}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 清空搜索历史 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| S-03-01 | 正向 | PASS | code=200 | code=200 | P5-02 |

#### 问题与修复

无

#### 请求/响应摘录

—

### S-04 DELETE /search/history/deleteOne/{id} {#s-04-delete-searchhistorydeleteoneid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 400 |
| body.msg | 删除搜索历史失败 |
| **结论** | 删除单条历史 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| S-04-01 | 关联 | PASS | 200/404 | code=400 | S04 |

#### 问题与修复

无

#### 请求/响应摘录

—

### S-05 GET /search/trending {#s-05-get-searchtrending}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 热门发现 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| S-05-01 | 正向 | PASS | code=200 | code=200 | P4-08 |

#### 问题与修复

无

#### 请求/响应摘录

—

#### 2026-09-20 热榜双层缓存改造：改造前基线

| 项 | 内容 |
|----|------|
| **性能验收状态** | `PARTIAL`（三轮改造前基线已完成；候选版本尚未实现） |
| 执行时间 | 2026-09-20 09:10:54～09:15:47（Asia/Shanghai） |
| 场景 | 热缓存稳态；先预热 30 秒，保留同一个 `search:trending:all`，正式轮次之间等待 15 秒 |
| 固定负载 | 20 线程，10 秒 ramp-up，每轮配置 60 秒，HTTP keep-alive |
| 断言 | HTTP 200；响应体 `code=200` |
| 代码标识 | branch=`feat/m4-eval-gates`；HEAD=`c6f9b8d8d360cb31ae548eea20fa0490e1914ffd`；工作树非干净（存在本任务外的 Bot/小程序改动，基线 JAR 从当前工作树重新构建） |
| 构建 | `mvn -DskipTests package`，Java 编译目标 release 17，BUILD SUCCESS |
| 主机 | Windows 11 家庭版中文版 10.0.26200；Intel Core Ultra 5 225H，14 核/14 逻辑处理器；31.43 GB 内存 |
| 运行时 | Oracle JDK 21.0.8；Spring Boot 3.5.11；Apache JMeter 5.6.3；Docker Desktop Engine 29.6.1 |
| 依赖 | 应用 `127.0.0.1:9191`；MySQL 8.0.43 `127.0.0.1:3306`；Redis 7 `127.0.0.1:6379` DB 1；RabbitMQ 3.13 `127.0.0.1:5674` |
| 数据规模 | `tb_content` 111（可见 65）；`tb_user` 35（未删除且未封禁 31）；`tb_user_search_history` 35；Redis DB 1 共 20 个 Key |
| 基线实现 | 仅 Redis 单层聚合缓存，Key=`search:trending:all`，配置 TTL=1800 秒，无 Caffeine L1 |
| API 实测 | HTTP 200、body.code=200；关键词 8、问题 10、校友 7 |
| 改造前全量测试 | 115 tests，0 failures，0 errors，1 skipped，`BUILD SUCCESS`，总耗时 02:05 |

##### 三轮明细

| 轮次 | 样本数 | 错误数 | 错误率 | 实际持续(s) | 吞吐(req/s) | Min(ms) | Max(ms) | Mean(ms) | Median(ms) | P90(ms) | P95(ms) | P99(ms) | Received(KB/s) | Sent(KB/s) | JTL SHA-256 |
|------:|-------:|-------:|-------:|------------:|------------:|--------:|--------:|---------:|-----------:|--------:|--------:|--------:|---------------:|-----------:|:-------------|
| 1 | 236195 | 0 | 0.00% | 59.914 | 3942.234 | 0 | 184 | 4.635 | 5 | 6 | 6 | 9 | 8372.285 | 504.329 | `713FA3D580DFDEF9069C630504C51D38616767991BED9FD959023347101AD68A` |
| 2 | 245909 | 0 | 0.00% | 59.917 | 4104.161 | 0 | 125 | 4.452 | 4 | 6 | 6 | 11 | 8716.175 | 525.044 | `716431A0CBD32B2469CEE1D02245BDBB2C04A527FB4D69024FE1E38AA854E451` |
| 3 | 250525 | 0 | 0.00% | 59.915 | 4181.340 | 0 | 114 | 4.369 | 5 | 7 | 7 | 11 | 8880.085 | 534.918 | `36AB70BF249690F2EC6070B5C3C0363BDE6A52BABEB728A9DDCCCB6930D46DBA` |
| **三轮中位数** | **245909** | **0** | **0.00%** | **59.915** | **4104.161** | **0** | **125** | **4.452** | **5** | **6** | **6** | **11** | **8716.175** | **525.044** | n/a |

##### 原始证据与当前结论

- 本地原始文件：`perf/results/baseline-r1.jtl`～`baseline-r3.jtl` 及对应 `baseline-rN-report/statistics.json`；目录被 `.gitignore` 排除，不提交仓库。
- 汇总工具：`perf/summarize-trending-results.ps1`；指标来自每轮 JMeter HTML 报告 `statistics.json` 的 `Total` 节点，持续时间由 JTL 首末样本时间戳计算，哈希由 `Get-FileHash -Algorithm SHA256` 计算。
- 三轮错误率均为 0；基线吞吐中位数为 4104.161 req/s，P95 中位数为 6 ms，P99 中位数为 11 ms。
- 当前只能确认改造前基线稳定，不能提前宣称双层缓存带来提升。完成候选版本三轮同协议测试后，再补绝对差值、百分比变化和最终状态。

#### 2026-09-20 热榜双层缓存改造：候选版本与最终结论

| 项 | 内容 |
|----|------|
| **性能验收状态** | `PARTIAL`：功能、一致性和降级测试通过；三轮候选压测未优于基线，用户决定暂缓性能优化与复测 |
| 执行时间 | 2026-09-20 14:00:51～14:06:03（Asia/Shanghai） |
| 场景 | 与基线相同的热缓存稳态；预热 30 秒，正式轮次之间等待 15 秒，使候选 L1 在每轮开始前过期、L2 保持热数据 |
| 固定负载 | 20 线程，10 秒 ramp-up，每轮配置 60 秒，HTTP keep-alive |
| 断言 | HTTP 200；响应体 `code=200`；三轮均 0 错误 |
| 代码标识 | branch=`feat/m4-eval-gates`；候选 HEAD=`04550e477f3f5966f7a7db7f03d666447173d36b`；工作树非干净，但本任务提交均按文件限定，未覆盖 Bot/小程序等用户改动 |
| 候选实现 | Caffeine L1（10 秒、1 条）→ Redis L2（240～360 秒抖动）→ 排行/MySQL；单 Key 墓碑 + Lua CAS 阻止旧 loader 覆盖失效 |
| API 实测 | HTTP 200、body.code=200；关键词 8、问题 10、校友 7 |
| 候选全量测试 | 168 tests，0 failures，0 errors，1 skipped，`BUILD SUCCESS`，总耗时 01:59 |
| 日志干扰说明 | 候选三轮期间 `SearchController` 每请求打印 INFO，候选 stdout 约 78 MB；基线同样启用该日志，但候选三轮波动明显，因此不把结果描述为稳定容量 |

##### 候选三轮明细

| 轮次 | 样本数 | 错误数 | 错误率 | 实际持续(s) | 吞吐(req/s) | Min(ms) | Max(ms) | Mean(ms) | Median(ms) | P90(ms) | P95(ms) | P99(ms) | Received(KB/s) | Sent(KB/s) | JTL SHA-256 |
|------:|-------:|-------:|-------:|------------:|------------:|--------:|--------:|---------:|-----------:|--------:|--------:|--------:|---------------:|-----------:|:-------------|
| 1 | 134133 | 0 | 0.00% | 59.844 | 2241.378 | 0 | 266 | 8.168 | 7 | 15 | 19 | 37 | 4760.109 | 286.739 | `5E01B9316D904310B037D35CE2641B2539FEFB8C5BB2F38F133DDCA59386D7B7` |
| 2 | 137225 | 0 | 0.00% | 59.821 | 2293.927 | 0 | 108 | 7.983 | 8 | 18 | 23 | 39 | 4871.710 | 293.461 | `CE268B447175855C1FA2EE389CDD8DF215EC0EC1D8606B04ED222929AFE300B9` |
| 3 | 232411 | 0 | 0.00% | 59.936 | 3877.653 | 0 | 116 | 4.713 | 7 | 16 | 21 | 37 | 8235.133 | 496.067 | `CA259BCE092C579BF4D2E4BA42CFD7809949B3280843FCFF02653FA502A1E60A` |
| **三轮中位数** | **137225** | **0** | **0.00%** | **59.844** | **2293.927** | **0** | **116** | **7.983** | **7** | **16** | **21** | **37** | **4871.710** | **293.461** | n/a |

##### 基线与候选中位数对比

| 指标 | 基线 | 候选 | 绝对变化 | 百分比变化 | 结论 |
|------|-----:|-----:|---------:|-----------:|------|
| 吞吐(req/s) | 4104.161 | 2293.927 | -1810.234 | -44.11% | 未提升 |
| Mean(ms) | 4.452 | 7.983 | +3.531 | +79.31% | 变差 |
| Median(ms) | 5 | 7 | +2 | +40.00% | 变差 |
| P95(ms) | 6 | 21 | +15 | +250.00% | 变差 |
| P99(ms) | 11 | 37 | +26 | +236.36% | 变差 |
| 错误率 | 0.00% | 0.00% | 0 | 0 | 均通过正确性断言 |

##### 功能与降级证据矩阵

| 场景 | 状态 | 证据与边界 |
|------|------|------------|
| L1 命中不访问 L2 | `PASS` | 单元测试通过；真实运行中先热 L1、再写坏 L2，立即请求仍为 200 且损坏值保持不变 |
| L1 过期后读取/修复 L2 | `PASS` | 真实运行等待 11 秒后请求为 200，损坏 JSON 被事实源结果替换，实测新 TTL=290 秒 |
| 正常 L2 TTL 抖动 | `PASS` | 真实运行首次回填 TTL=318 秒；后续回填 TTL=287/290/355 秒，均位于 240～360 秒 |
| 失效墓碑后新数据回填 | `PASS` | 真实运行写入 `__INVALIDATED__:runtime-check` 后请求为 200，墓碑被正常 JSON 替换，TTL=355 秒 |
| stale-backfill 竞态 | `PASS` | `TrendingCacheRedisIntegrationTests` 使用真实 Redis 覆盖双实例旧 loader 晚于墓碑、正常回填早于失效两种顺序；最终值分别保持墓碑，并覆盖真实损坏 JSON 条件替换 |
| Redis 读取异常降级 | `PASS` | 单元测试证明回源结果可进入 L1、该次禁止写 L2；MySQL 异常继续向上抛出，不伪造空榜单 |
| 事务提交后失效、回滚不失效 | `PASS` | 失效器测试覆盖无事务立即执行、提交后执行一次、回滚不执行；内容审核/删除、管理员删除、账号封禁与解封定向测试通过 |
| Redis 在事务提交时不可用 | `PARTIAL` | 代码记录告警并保留明确边界：旧 L2 可能存活到剩余 TTL，上限 360 秒；本轮未中断共享 Redis 做运行态破坏性验证 |
| 多实例 L1 广播 | `PARTIAL` | 本阶段明确不实现 Pub/Sub；其他实例最多保留 10 秒旧值，为已接受一致性边界 |

##### 最终结论

- 功能实现、HTTP 契约、真实 Redis 竞态和全量回归均通过；缓存正确性目标已完成。
- 性能目标未通过：候选吞吐中位数较基线下降 44.11%，P95/P99 均升高；三轮候选吞吐为 2241/2294/3878 req/s，波动明显，不能宣称性能提升或生产容量。
- 按用户 2026-09-20 决策，本轮不继续处理性能问题，不删除已实现的一致性能力；性能验收保留为 `PARTIAL`，后续若恢复该项，应先关闭逐请求 INFO 日志，再在相同应用启动时长和系统负载下同时重跑基线与候选。
- 原始文件位于 `perf/results/candidate-r1.jtl`～`candidate-r3.jtl` 及对应报告目录，均由 `.gitignore` 排除；上表保留全部哈希用于核对。
- 独立代码复核曾提出 4 个 Important：DB 兜底因重复项补不满、审核外围副作用失败时可能漏登记失效、真实 Redis 竞态顺序覆盖不足、审核测试只验证 mock 曝光调用。均已修复：兜底按完整上限查询；审核事务拥有者在数据库状态变化后立即登记 `afterCommit`；新增反向顺序和损坏 JSON 真实 Redis 测试；审核测试直接验证失效登记。另将墓碑 TTL 改为固定 360 秒。
- 同一独立复核者对修复提交 `30b9595` 二次只读确认：4 个 Important 与 1 个 Minor 均为 `Resolved`，结论 `Ready to merge: Yes`。
- 复核修复后定向测试 40 tests，0 failures，0 errors；最终全量测试 168 tests，0 failures，0 errors，1 skipped，`BUILD SUCCESS`，总耗时 01:57。

## 用户端 /follow、/rag、/common

### F-01 POST /follow/{id} {#f-01-post-followid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 关注用户 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| F-01-01 | 正向 | PASS | code=200 | code=200 | P5-14 |
| F-01-02 | 业务 | FAIL | code=400 | code=500 | 不能关注自己 |

#### 问题与修复

无

#### 请求/响应摘录

—

### F-02 GET /follow/feed {#f-02-get-followfeed}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 关注 Feed |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| F-02-01 | 正向 | PASS | code=200 | code=200 | P4-18 |

#### 问题与修复

无

#### 请求/响应摘录

—

### R-01 POST /rag/search {#r-01-post-ragsearch}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | RAG 检索正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| R-01-01 | 正向 | PASS | code=200 | code=200 | P4-10 |
| R-01-02 | 非法 | PASS | code=400 | code=400 | P4-11 |

#### 问题与修复

无

#### 请求/响应摘录

—

### O-01 POST /common/upload {#o-01-post-commonupload}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 上传成功 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| O-01-01 | 正向 | PASS | code=200 | code=200 | P2-01 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 管理端 /admin

### AD-01 GET /admin/content/page {#ad-01-get-admincontentpage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 待审列表正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-01-01 | 正向 | PASS | code=200 | code=200 | P3-01 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-02 POST /admin/content/audit {#ad-02-post-admincontentaudit}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 审核通过专业帖 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-02-01 | 正向 | PASS | code=200 | code=200 | P3-04 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-03 DELETE /admin/content/{contentId} {#ad-03-delete-admincontentcontentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 400 |
| body.msg | 内容不存在 |
| **结论** | 管理端删帖（含不存在 ID） |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-03-01 | 关联 | PASS | 404/400 | code=400 | P7-12 |
| AD-03-02 | 正向 | SKIP | code=200 | - | 测试帖已删 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-04 GET /admin/content/report/page {#ad-04-get-admincontentreportpage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 帖子举报分页 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-04-01 | 正向 | PASS | code=200 | code=200 | P6-08 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-05 POST /admin/content/report/handle {#ad-05-post-admincontentreporthandle}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 处理举报 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-05-01 | 正向 | PASS | code=200 | code=200 | P6-09 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-06 GET /admin/user/page {#ad-06-get-adminuserpage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 管理员分页正常 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-06-01 | 正向 | PASS | code=200 | code=200 | P1-07 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-07 GET /admin/user/{id} {#ad-07-get-adminuserid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 用户详情 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-07-01 | 正向 | PASS | code=200 | code=200 | P6-02 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-08 POST /admin/user/ban/{userId} {#ad-08-post-adminuserbanuserid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 封禁用户B |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-08-01 | 正向 | PASS | code=200 | code=200 | P6-04 |
| AD-08-02 | 业务 | PASS | code=401 | code=401 | 封禁后登录 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-09 POST /admin/user/unban/{userId} {#ad-09-post-adminuserunbanuserid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 解封用户B |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-09-01 | 正向 | PASS | code=200 | code=200 | P6-06 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-10 GET /admin/comment/page {#ad-10-get-admincommentpage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 评论分页 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-10-01 | 正向 | PASS | code=200 | code=200 | P6-11 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-11 DELETE /admin/comment/{commentId} {#ad-11-delete-admincommentcommentid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 400 |
| body.msg | 评论不存在 |
| **结论** | 管理端删评论 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-11-01 | 正向 | PASS | 200/404/400 | code=400 | P7-08 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-12 GET /admin/comment/report/page {#ad-12-get-admincommentreportpage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 评论举报分页 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-12-01 | 正向 | PASS | code=200 | code=200 | P6-12 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-13 POST /admin/comment/report/handle {#ad-13-post-admincommentreporthandle}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 处理评论举报 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-13-01 | 正向 | PASS | 200/404 | code=200 | P6-13 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-14 GET /admin/answer/page {#ad-14-get-adminanswerpage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 回答分页 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-14-01 | 正向 | PASS | code=200 | code=200 | P6-14 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-15 DELETE /admin/answer/{answerId} {#ad-15-delete-adminansweranswerid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 400 |
| body.msg | 回答不存在 |
| **结论** | 管理端删回答 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-15-01 | 正向 | PASS | 200/404/400 | code=400 | P7-09 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-16 POST /admin/answer/audit {#ad-16-post-adminansweraudit}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 审核回答 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-16-01 | 正向 | PASS | code=200 | code=200 | P6-15 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-17 GET /admin/identityExam/page {#ad-17-get-adminidentityexampage}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 实名分页 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-17-01 | 正向 | PASS | code=200 | code=200 | P6-16 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-18 GET /admin/identityExam/userAuth/detail/{authId} {#ad-18-get-adminidentityexamuserauthdetailauthid}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 实名详情 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-18-01 | 正向 | PASS | code=200 | code=200 | P6-17 |

#### 问题与修复

无

#### 请求/响应摘录

—

### AD-19 POST /admin/identityExam/audit {#ad-19-post-adminidentityexamaudit}
| 项 | 内容 |
|----|------|
| **接口状态** | `PASS` |
| 最后执行 | 2026-05-19 |
| HTTP | 200 |
| body.code | 200 |
| body.msg | success |
| **结论** | 实名审核 |

#### 用例明细

| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |
|--------|------|------|------|------|------|
| AD-19-01 | 正向 | PASS | code=200 | code=200 | P6-18 |

#### 问题与修复

无

#### 请求/响应摘录

—

## 全局问题汇总（跨接口）

| 编号 | 关联接口 | 问题描述 | 严重程度 | 状态 |
|------|----------|----------|----------|------|
| G-01 | C-01 | `publish` 未设 `collectCount` 导致插入异常→500 | 高 | **已修复**（2026-05-19） |
| G-03 | R-01 | `POST /rag/search` 空 query 返回 HTTP 500，预期 400 | 中 | **已修复**（2026-05-19，`GlobalExceptionHandler` 处理 `@Valid` 校验异常→400） |
| G-02 | C-01 | 发布默认 `auditStatus=已通过`（L154 TODO），与「待审」设计不一致 | 低 | 待产品确认 |
