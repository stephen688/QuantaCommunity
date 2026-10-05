# 按 traceId 排查 Java / Bot 链路

本文对应 [实施计划](plans/2026-10-05-trace-id-correlated-logging.md)。功能和测试状态以 [RESULTS](api-test/RESULTS.md) 的本轮记录为准；配置存在不等于生产已经部署。

## 请求编号怎样使用

HTTP 请求/响应使用 `X-Request-Id`，日志显示 `traceId=编号`。不带编号时后端生成 32 位 hex；调用方传入的编号只能包含字母、数字、下划线、连字符，长度 1–64。非法或重复请求头会被替换，不导致业务请求被拒绝。

同一编号从创建事件时进入 Outbox `trace_id`，由 RabbitMQ 的 `X-Request-Id` 消息头传到消费者和 Bot；Bot 调主服务时继续携带。`X-Event-Id`/日志 `eventId` 表示具体事件；它和 traceId 都不能代替用户权限、业务 ID 或幂等凭证。

管理员审计表仍保存管理员操作，只是 request_id 复用入口编号。人工重放的管理员请求拥有自己的编号，重放事件沿用原事件编号，用 eventId 关联两者。历史无编号事件按 eventId 稳定生成编号，无法还原它原来的 HTTP 请求。

HTTP 200 不表示业务码成功或 Bot 已可见。`comment_submission_committed` 表示评论事务提交，`comment_audit_committed` 才说明该次审核状态已经提交；外层审核消费者的失败/重试日志也需要一起看。

## 日志在哪里

本地启动工作目录为 `demo0/` 时，Java 默认目录：

```text
C:\Users\dwc12\Desktop\QuantaCommunity\demo0\logs\demo0.log
```

Bot 默认目录固定锚到项目根，和启动工作目录无关：

```text
C:\Users\dwc12\Desktop\QuantaCommunity\QuantaBot\data\logs\quantabot.log
```

Bot 的现有 compose 将宿主 `QuantaBot/data` 挂载到 `/app/data`；容器中日志也保存在该挂载内。文件保留不依赖容器进程存活，删除宿主 data 则会删除相关运行数据。

服务器路径由运行配置决定，不是自动写回开发电脑。Java 推荐设置 `QUANTA_LOG_FILE` 为运行账号可写的绝对路径；Bot 可设置 `QUANTABOT_LOG_FILE` 为绝对路径。相对 Java 路径以启动工作目录为准。

## 怎样搜索

拿到响应头编号后，从仓库根目录运行（`trace-check-20261005-A` 替换为实际编号）：

```powershell
rg -n -F 'traceId=trace-check-20261005-A' demo0/logs QuantaBot/data/logs -g '*.log'
```

Linux 服务器同样可以在配置的两服务日志目录使用 `rg -n -F`；多个机器需要分别查，第一期未配置集中日志平台。

归档是 gzip，普通文本搜索不会自动解压。以下 PowerShell 命令只读某一个归档：

```powershell
$traceArchive = 'C:\实际日志目录\demo0.log.2026-10-05.0.gz'
$traceInput = [IO.File]::OpenRead($traceArchive)
$traceGzip = [IO.Compression.GZipStream]::new($traceInput, [IO.Compression.CompressionMode]::Decompress)
$traceReader = [IO.StreamReader]::new($traceGzip, [Text.Encoding]::UTF8)
try {
    $traceReader.ReadToEnd() -split "`n" | Select-String -SimpleMatch 'traceId=trace-check-20261005-A'
} finally {
    $traceReader.Dispose()
    $traceGzip.Dispose()
    $traceInput.Dispose()
}
```

按同号依次检查：请求进入/结束 → 评论事务提交 → Outbox 已追加/投递 → Java 消费/派生事件 → Bot 消费和最终决策 → Java 写回 → 二次审核。日志中“监听返回”只表示监听方法返回；业务失败可能已在内部安排重试，不能当作消费成功。

Langfuse 保留现有由 commentId 派生的运行 ID；其根 observation 的 metadata 中可通过 `business_trace_id` 和 `event_id` 关联原请求。业务 traceId 可以是短字符串，不能直接当作 Langfuse 的 32 位 hex ID；程序可用 `Langfuse.create_trace_id(seed="run-评论ID")` 计算现有运行 ID。

## 保存和清理

日志持续写入，UTC 跨日或达到单文件大小时滚动，不是一天只保存一次。正常退出会 flush/排空队列；断电、强杀可能损失尾部日志，不保证逐条强制写入物理磁盘。

默认单文件约 20MB，归档保留最多 14 天、总量约 300MB。Bot 按 MiB 字节值执行，Java 使用 Logback 的 MB 配置。历史达到容量上限会先删除旧归档，所以高流量下不保证保留完整 14 天；活动文件额外占空间，清理/压缩期间会暂时超出归档预算。

| Java 环境变量 | 默认值 |
|---|---|
| QUANTA_LOG_FILE | logs/demo0.log |
| QUANTA_LOG_MAX_FILE_SIZE | 20MB |
| QUANTA_LOG_MAX_HISTORY | 14 |
| QUANTA_LOG_TOTAL_SIZE_CAP | 300MB |

| Bot 环境变量 | 默认值 |
|---|---|
| QUANTABOT_LOG_FILE | data/logs/quantabot.log |
| QUANTABOT_LOG_LEVEL | INFO |
| QUANTABOT_LOG_MAX_BYTES | 20971520 |
| QUANTABOT_LOG_RETENTION_DAYS | 14 |
| QUANTABOT_LOG_TOTAL_SIZE_BYTES | 314572800 |

Bot 不同进程/worker 不能共同写一个滚动文件；每进程独立路径或使用外部日志采集。当前方案按单消费者/单服务进程配置，不引入多个 worker。

文件目录不可写、磁盘满或队列关闭问题应在控制台留下明确错误；控制台还能打印不代表文件已保存。启动输出、连接回调或没有业务来源的日志可能显示 `traceId=-`，这不表示发生了业务失败。

时间戳和归档日切使用 UTC（`+00:00`/`Z`）；北京时间需加 8 小时。例如北京时间 10 月 5 日 07:30 对应 UTC 10 月 4 日 23:30，要查 10 月 4 日的归档。

## 发布与回退

1. 先检查目标库的 `tb_outbox_event` 是否已有 trace_id，再执行 `src/main/resources/db/V_trace_id_logging.sql` 新增可空列；这是显式迁移，启动不自动执行。
2. 发布 Java，再发布 Bot；旧 Bot 会忽略消息头，旧事件使用稳定兜底。验证需要用新事件，不能把旧事件称为恢复了原请求编号。
3. 创建/授权可写日志目录，注入实际环境变量；`.env.example` 只是模板，Java 本身不会自动加载仓库根 `.env`。
4. 验证响应头、文件输出、一次正常业务和一次受控失败。证据写入 RESULTS，不将 HTTP 200 代替最终审核/可见性。
5. 回退旧应用时保留新增数据库列和日志，不删除 Outbox/Inbox、SQLite 或历史归档；Java 如需只输出控制台，应移除部署配置的 `logging.file` 和文件滚动设置，Bot 可用空 `QUANTABOT_LOG_FILE` 关闭文件保存。

归档 `.gz` 位于已忽略的日志目录，不提交到 Git。不要将真实 Token、密码、评论原文或整份生产日志粘进结果文档。
