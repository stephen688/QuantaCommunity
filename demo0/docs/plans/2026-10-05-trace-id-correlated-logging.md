# traceId 关联日志与持久化 Implementation Plan

> **For agentic workers:** 按本计划逐任务执行；用户明确要求「不要过度测试或是分太多 task」，因此只设 4 个任务。2026-10-05 用户授权「开始执行，合理派子agent」，请求入口、Java 异步链路、Bot 接入可并行，协调者负责整合与最终验收。实施前使用 `test-driven-development`；完成后按项目规范使用 `requesting-code-review`。

**Goal:** 用户提供一次请求的编号后，可以在已保存的 Java/Bot 日志中，定位 HTTP → Outbox → RabbitMQ → 消费 → Bot → 写回 → 审核链路的关键结果与失败原因。

**Architecture:** Java 请求入口建立 MDC 上下文，Outbox 在创建时持久化关联编号，MQ 使用消息头传递；Java 消费和线程池入口恢复上下文，Python 使用 ContextVar。保留现有 Langfuse 运行标识，通过 metadata 关联业务 traceId。运行日志持续写文件并自动滚动、压缩和清理，管理员审计继续写原有表。

**Tech Stack:** 当前 `demo0/pom.xml` 的 Spring Boot 3.5.11 / Java 17 配置、SLF4J/Logback、Spring AMQP、MyBatis/MySQL；QuantaBot Python 3.12、标准库 logging/contextvars、httpx、aio-pika、现有 Langfuse SDK。不升级依赖，不新增追踪平台。

**Spec:** 本文件「需求结论与契约」是本次会话确认范围的落盘版本；项目位置对应 `demo0/docs/后续demo0优化总方案.md` 的 traceId 专项，以及 `QuantaBot/总计划.md` 的 M5 后工程维护项。实现权威入口为 `demo0/AGENTS.md`、`demo0/docs/outbox-plan.md`、`QuantaBot/AGENTS.md` 和 `QuantaBot/docs/技术选型.md`。

**状态:** 实现、本地边界验收和独立审查已完成。真实执行结果见 RESULTS.md 的 S-TRACE；未发布，目标业务库的迁移仍为切换新版本的前置步骤。

## Global Constraints

- 用户要求：「不要过度测试或是分太多task」。只保留下面 4 个任务；测试测行为边界，不为每个字段、日志文案或消费者复制一套测试。
- 本次范围是 `demo0` 和 `QuantaBot` 的后端日志关联与日志保存。仅回传响应头、暴露 CORS 响应头，不修改管理端/小程序页面，不增加“复制编号”交互。
- 不做日志查询 API、自研日志面板、ELK/Loki 部署、Prometheus/Grafana、OpenTelemetry/span 拓扑、压测或人格评测。已有 Langfuse 继续使用。
- traceId 只作关联检索，不用于鉴权、幂等、业务状态判定或重试次数；eventId、commentId、Idempotency-Key 保持原职责。
- 不改变 HTTP 状态/Result 外壳、匿名可选鉴权、Service Token、业务与 Outbox 同事务、Inbox 去重、租约、Confirm/Return、ACK/NACK、重试/死信和 Bot 静默规则。
- 保留现有改动。工作区已存在 Feed 浏览事件、API 结果、总方案、Bot 环境模板和 compose 等未提交变更；不能覆盖，不能使用 `git add -A`。
- 新增 Java 类沿用所属包风格、文件头职责/边界注释和公开方法 Javadoc。Python 新模块有中文头注释/docstring，配置只从 Settings 读取，外部客户端仍在 composition 创建。
- 不记录密码、Authorization/Token、完整请求头、完整 payload、评论正文、模型 prompt/输出或带签名 URL。现有 Langfuse 的内容观测范围不在本次扩大。
- 本次已获实现和必要本地验证授权；没有提交、推送或生产部署授权。真实开发数据库保持不变，链路验证使用隔离数据库/RabbitMQ vhost/Redis 和独立端口，不影响已运行的 9191 服务。

## 需求结论与契约

### 1. traceId 的含义与边界

同一次操作及其异步副作用共用一个关联编号，不是给每条日志生成不同编号。启动、连接重建等没有请求来源的日志使用 `traceId=-`。定时任务独立产生事件时按 eventId 生成稳定编号，不冒充原用户请求。

HTTP 唯一线缆字段为 `X-Request-Id`，Java MDC 字段为 `traceId`，Python LogRecord 展示字段同为 `traceId`。内部实体属性 `traceId` / Python `trace_id`，MySQL 列 `trace_id`。不再增加一套 `X-Trace-Id`。

- 默认生成 UUID 去连字符得到的 32 位小写 hex。
- 兼容调用方已有 UUID/短 requestId：入站只接受完整匹配 `[A-Za-z0-9_-]{1,64}` 的单一值，原样保留；缺失、重复请求头、超长、控制字符或其他非法值均重新生成，不拒绝原业务请求，也不打印非法原值。
- 外部编号不可信，可能被调用方复用；排查时结合时间、服务与 eventId，不宣称所有外部编号全局唯一。
- 审计 `request_id` 复用本次入口确定的编号，不再每条审计独立生成 UUID。脱离 HTTP 的现有审计行为按原边界处理。
- MQ headers 使用 `X-Request-Id` 和 `X-Event-Id`；payload 的业务 DTO 不新增 trace 字段。重试/死信经 ReliableRabbitPublisher 重建消息时重新写这两个头。
- 没有编号的历史 Outbox/MQ 事件采用 `SHA-256(UTF-8("event:" + eventId))` 的前 32 个 hex 字符；Java/Python 必须一致。没有合法 eventId 的毒丸使用随机编号，不改变原丢弃/死信规则。
- 人工重放沿用原事件编号；管理员这次点击重放的审计使用管理员请求编号，依靠 eventId 连接两次操作，不覆盖原事件 traceId。
- 同 Idempotency-Key 再次请求可以有新的请求编号，但不能覆盖首次已存在 Outbox 的 trace_id；记录重复命中/原业务 ID，不把幂等凭证直接写日志。

### 2. 日志内容与保存默认值

统一基础字段：带时区的时间、级别、service、线程/logger、traceId、eventId（没有则 `-`）、阶段及结果。Java 日志加 `%X{traceId:-}` / `%X{eventId:-}`；Python LogRecord 在产生日志的线程/协程捕获 ContextVar，不在后台写文件时读取。

关键记录范围：HTTP 结束与耗时；Outbox 创建、投递、重试/DEAD；消费开始、已有成功/业务处理结果/失败/重投；Bot 收到触发、最终决策、写回提交结果；评论审核结果。复用已有日志，只补缺口，不给所有方法加进入/退出日志。

HTTP `status=200` 只表示 HTTP 返回，不代表 Result 业务成功或异步最终可见；提交日志写“事务提交/提交写回”，审核日志才记录最终审核结果。HTTP 结束日志不记录 queryString/requestBody。

可配置默认值（实施方案默认，不是用户指定的生产容量）：

| 项目 | Java | Bot |
|---|---|---|
| 当前文件 | `demo0/logs/demo0.log`（从 demo0 工作目录启动） | `QuantaBot/data/logs/quantabot.log`（通过 resolve_data_path 锚定） |
| 滚动 | UTC 跨日或单文件达到 20MB | UTC 跨日或单文件达到 20MiB |
| 历史保留 | 14 天；归档总量 300MB，先到者先清理 | 14 天；归档总量 300MiB，先到者先清理 |
| 归档 | 日期 + 序号，gzip | 日期/时间 + 序号，gzip |
| 控制台 | 保留 | 保留 |
| 写入 | Logback 默认文件 appender，同步交给操作系统 | QueueHandler/QueueListener 后台写文件 |

日志持续写入，不是每天定时批量存储；跨日切换通常由下一条日志触发。保留限制针对归档，当前活动文件额外占空间；压缩/删除期间可有短暂超限，不能宣传硬实时磁盘配额。正常关闭 flush，强制杀进程/断电可能丢缓存或队列中的尾部日志；不逐条 fsync。

Bot 使用单进程写一个日志文件，符合当前 uvicorn/单消费者部署；多 worker/多实例必须各自指定文件或交给采集平台，不能共享一个滚动文件。复用 compose 的 `../data:/app/data`，不为日志再加一个 Docker 卷。

## 当前源码证据与实施文件地图

所有路径相对 `QuantaCommunity/` 根目录。下表文件为实施时的计划清单，本次不修改这些实现文件。

| 任务 | 新增 | 修改 |
|---|---|---|
| 1 请求入口 | `demo0/src/main/java/com/quanta/demo0/platform/web/trace/TraceContext.java`、`RequestTraceFilter.java`、`TraceConfiguration.java`；同包测试 `demo0/src/test/java/com/quanta/demo0/platform/web/trace/RequestTraceFilterTest.java` | `demo0/src/main/java/com/quanta/demo0/platform/security/config/SecurityConfiguration.java`（仅 exposed headers）、`platform/audit/service/impl/AdminAuditRecorderImpl.java`、`platform/web/handler/GlobalExceptionHandler.java`；`platform/security/SecurityFilterChainTests.java` 测试 |
| 2 Java 异步链路 | `demo0/src/main/java/com/quanta/demo0/platform/mq/trace/RabbitTraceAdvice.java`；`demo0/src/main/resources/db/V_trace_id_logging.sql`；`demo0/src/test/java/com/quanta/demo0/platform/mq/trace/RabbitTraceAdviceTest.java` | Java `platform/mq/entity/OutboxEvent.java`、`platform/mq/producer/OutboxEventAppender.java`、`platform/mq/service/impl/OutboxEventServiceImpl.java`、`platform/mq/outbox/OutboxDispatcher.java`、`platform/mq/producer/ReliableRabbitPublisher.java`、`platform/mq/config/RabbitMQConfig.java`；`demo0/src/main/resources/mapper/platform/mq/OutboxEventMapper.xml`；`rag/retrieval/RagRetrieveFacade.java`、`content/service/impl/ContentTopicTagServiceImpl.java`；已有 `reliability/ReliabilityMySqlIntegrationTests.java`、`reliability/OutboxRabbitIntegrationTests.java` |
| 3 Bot 接入 | `QuantaBot/src/quanta_bot/crosscutting/trace_context.py`、`QuantaBot/src/quanta_bot/infra/logging_config.py`；`QuantaBot/tests/unit/infra/test_logging_config.py` | `QuantaBot/src/quanta_bot/consumer.py`、`server.py`、`infra/main_service.py`、`infra/settings.py`、`infra/tracing.py`、`pipeline/ports.py`、`pipeline/pipeline.py`；已有 `tests/unit/test_consumer.py`、`tests/unit/infra/test_main_service.py`、`tests/unit/infra/test_tracing.py` |
| 4 落盘与收口 | `demo0/docs/trace-id-troubleshooting.md` | `demo0/src/main/resources/application.yml`、根 `.gitignore`、`demo0/.env.example`（只补日志目录项）、`QuantaBot/.env.example`；`demo0/src/main/java/com/quanta/demo0/comment/service/impl/CommentCommandServiceImpl.java`、`CommentAuditServiceImpl.java`；`demo0/docs/outbox-plan.md`、`demo0/docs/后续demo0优化总方案.md` 第 10 项、`demo0/docs/api-test/cases/07-bot.http`、`demo0/docs/api-test/RESULTS.md`、`QuantaBot/docs/技术选型.md`、`QuantaBot/总计划.md` |

Java 表格中的缩写包路径均位于 `demo0/src/main/java/com/quanta/demo0/`；缩写测试路径位于 `demo0/src/test/java/com/quanta/demo0/`。

现状：HTTP 只有审计局部读取 X-Request-Id；OutboxEvent/Mapper 没有 trace 列；Dispatcher 定时发送；重试 Producer 汇合到 ReliableRabbitPublisher；RabbitMQConfig 的回调有 `System.err`，Return 直接打印对象可能包含 payload；Java 消费者均走 RabbitListener。Bot 标准库日志缺少统一文件 handler；消费者读取消息 body，MainServiceClient 只带 Authorization；Langfuse ID 当前由 commentId 派生。SQLite 决策日志已落盘，不在本次新增列或双写 MySQL。

## Task 1：HTTP 编号、MDC 生命周期与审计复用

**产出接口：**

```java
// TraceContext.java：纯上下文工具，无 Spring/业务依赖。
public static String resolveHttp(String candidate); // 合法值保留，否则生成
public static String resolveEvent(String candidate, String eventId); // 合法值或稳定兜底
public static String currentTraceId(); // 无上下文返回 null
public static Scope open(String traceId, String eventId); // Scope implements AutoCloseable
public static <T> java.util.function.Supplier<T> wrapSupplier(
        java.util.function.Supplier<T> supplier); // 调用时捕获、执行时恢复全部 MDC
```

`Scope.close()` 恢复进入前的上下文；不能 `MDC.clear()` 粗暴清掉其他组件的键。HTTP 最外层在正常结束/异常时恢复原上下文，线程复用不能串号。编号写入 request attribute，ERROR/ASYNC redispatch 复用 attribute；现有同步接口是第一期验收范围，不宣称自动覆盖所有未来异步 Servlet 任务。

- [ ] **先写并跑失败测试。** 在 RequestTraceFilterTest 中以 MockHttpServletRequest/Response 直接执行 filter；用一次参数化覆盖合法/非法/缺失头，用一例覆盖下游抛异常后的清理。关键断言如下（测试类位于与 filter 相同包）：

```java
String traceId = "0123456789abcdef0123456789abcdef";
request.addHeader("X-Request-Id", traceId);
filter.doFilter(request, response, (req, res) ->
        assertEquals(traceId, MDC.get("traceId")));
assertEquals(traceId, response.getHeader("X-Request-Id"));
assertNull(MDC.get("traceId"));
```

运行：在 `demo0/` 执行 `mvn "-Dtest=RequestTraceFilterTest" test`。RED 可以是新增类不存在或预期行为缺失，不能把环境失败算 RED。

- [x] **实现入口。** 使用单个 FilterRegistrationBean 注册 RequestTraceFilter，order 为 `Ordered.HIGHEST_PRECEDENCE + 10`，覆盖 Spring Security 之前的请求；filter 不加 `@Component`、不再放进 SecurityFilterChain，避免重复执行。响应提交前设头，记录 `http_request_completed method/path/status/durationMs`；未知系统异常改为 `log.error("系统异常", ex)`，去掉单独 printStackTrace，保留原返回。

```java
String traceId = resolveFromSingleHeaderOrAttribute(request);
request.setAttribute("quanta.traceId", traceId);
response.setHeader("X-Request-Id", traceId);
try (TraceContext.Scope ignored = TraceContext.open(traceId, null)) {
    filterChain.doFilter(request, response);
} // 实际完成日志写在 scope 内 finally；不捕获并改写业务异常。
```

`resolveFromSingleHeaderOrAttribute(HttpServletRequest)` 是 RequestTraceFilter 的私有方法：先复用合法 attribute，否则检查 getHeaders 的值数量为 1，再调用 resolveHttp。AuditRecorder 优先 currentTraceId，再用 resolveHttp 兜底。SecurityConfiguration 只增加 `setExposedHeaders(List.of("X-Request-Id"))`。

- [x] **一次定向验证。** `mvn "-Dtest=RequestTraceFilterTest,SecurityFilterChainTests" test`；现有安全链用例增加 401/403 响应头与 CORS 暴露断言。MockMvc 的安全测试显式把外层 filter 加入，不能假设 apply(springSecurity()) 会注册 Servlet filter。不改返回契约。

- [x] **检查任务差异。** 核对新增注释、编号校验、过滤器只注册一次、异常恢复，确认本任务未碰鉴权规则。仅在后续明确授权提交时，以本任务精确文件清单提交 `feat: correlate HTTP logs and admin audit requests`。

## Task 2：Outbox 持久化、MQ 消费/重试及 Java 线程池传递

**消费 Task 1 的 TraceContext。产出：** Outbox `traceId` 和 MQ headers；RabbitTraceAdvice 使用原始 Message 建立 scope，覆盖监听调用/消息转换，退出时恢复。

- [ ] **先补关键失败断言。** 复用 ReliabilityMySqlIntegrationTests 的真实 MySQL Mapper 装配，追加一例：MDC 建立 A → append → 清掉 MDC → 查回/claim 仍为 A；appendIfAbsent 以 B 重投相同 eventId，旧 traceId 仍为 A。只扩展本类的临时表 schema，保留已有用户改动。RabbitTraceAdviceTest 一例包含消费者异常后恢复；已有 OutboxRabbitIntegrationTests 加一例真实 Rabbit header 接收，并在原有失败/重试案例增加同号断言，不复制整套可靠性测试。

```java
try (TraceContext.Scope ignored = TraceContext.open("request-A", null)) {
    appender.appendIfAbsent(eventId, "NOTIFICATION_REQUESTED", "CONTENT", 10L, message);
}
try (TraceContext.Scope ignored = TraceContext.open("request-B", null)) {
    appender.appendIfAbsent(eventId, "NOTIFICATION_REQUESTED", "CONTENT", 10L, message);
}
assertEquals("request-A", outboxMapper.selectByEventId(eventId).getTraceId());
```

这段断言加入现有有真实 Mapper 的测试，`appender/eventId/message/outboxMapper` 使用该测试实际装配/fixture；禁止只 mock Mapper 返回 A 来证明持久化。

- [x] **实现持久化和传递。** 新迁移只新增可空列，旧记录不回填、不加 trace 索引、不改 Inbox 表。迁移前检查 information_schema 是否已有列；脚本为显式一次执行，不伪装自动迁移：

```sql
ALTER TABLE tb_outbox_event
    ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '日志关联编号，非幂等键';
```

Appender 创建事件时读取 currentTraceId；Service.normalize 对未提供编号的事件按 resolveEvent 兜底，兼容直接调用 append 的测试/工具。XML 的 insert/insertIfAbsent、selectByEventId、selectClaimableForUpdate、pageAdmin 等显式字段投影全部带 trace_id；`ON DUPLICATE KEY UPDATE event_id=event_id` 不改。Dispatcher 对每条事件先 open scope，再发送、处理 Confirm/Return/重试，避免整个 batch 共用编号。

```java
// Dispatcher 和 ReliableRabbitPublisher 均采用 MessagePostProcessor 重载。
mqMessage.getMessageProperties().setHeader("X-Request-Id", traceId);
mqMessage.getMessageProperties().setHeader("X-Event-Id", eventId);
return mqMessage;
```

ReliableRabbitPublisher.send 的原有签名和可靠发布判断不变，从当前 scope 取 traceId，缺失则按 eventId 兜底；重试 Producer 无需各自增加字段。

RabbitMQConfig 增加默认 `rabbitListenerContainerFactory`：使用 Boot 的 SimpleRabbitListenerContainerFactoryConfigurer 先应用已有 manual ACK、prefetch 等配置，然后挂 RabbitTraceAdvice，不手写另一份参数。Advice 从 MethodInvocation 参数中的原始 Message 提取两个头；X-Event-Id 缺失时，对不超过现有 32KB 契约的 body 使用现有 ObjectMapper 只读取根字段 eventId（不打印 body），兼容旧消息。事件 ID 必须是长度不超过 128、完整匹配 `[A-Za-z0-9_.:-]+` 的字符串；trace 无效时按合法 eventId 稳定兜底，无合法事件 ID 时生成随机号。解析失败不能代替消息转换器拒绝业务，继续原消费/毒丸规则。不能仅在 afterReceivePostProcessor 中设置 MDC，因为它没有覆盖消费结束的清理。

所有 Java RabbitListener 通过默认 factory 自动覆盖；不改 9 个领域消费者的签名/分支。公共 advice 只记 `mq_listener_started/returned/threw` 及 durationMs；returned 不等于业务成功，原消费者已处理的成功/重试/死信日志才是结论。listener 吞掉异常安排重试时，不能被公共日志标记成功。

RabbitMQConfig 的 Return 回调从 returned.message headers 临时恢复 scope，只打印 exchange/routingKey/replyCode、eventId；不打印 returned 对象/body。Confirm 回调不依赖回调线程 MDC，只记录 correlationId/ack；Dispatcher/可靠发布器已有等待确认的日志是带 traceId 的结果依据。

两处 CompletableFuture 调用使用调用时捕获的 wrapSupplier，保持原超时、取消、降级和线程池：

```java
CompletableFuture.supplyAsync(TraceContext.wrapSupplier(() -> {
    // 保留 RagRetrieveFacade 的 ES / 向量召回原逻辑。
    return esRecallService.recall(query, contentType, esTopK);
}));
```

ContentTopicTagServiceImpl.callModel 同样包装 chatModel 调用。只承诺已盘点这两处的上下文传播；新建线程/未来 executor 需要继续显式包装。

- [x] **一次 Java 边界验证。** 在 `demo0/`、Docker 可用且迁移 fixture 更新后执行 `mvn "-Dtest=RabbitTraceAdviceTest,ReliabilityMySqlIntegrationTests,OutboxRabbitIntegrationTests,ConsumerReliabilityTests,NotificationConsumerReliabilityTest" test`。保持现有 ACK、失败重投和幂等断言；不为每个消费者写重复上下文测试。额外在请求测试类补一例线程池作用域传播/恢复，不另立测试工程。

- [x] **检查迁移与回滚。** 确认编号从数据库查回后发送，重试和重复插入不换号；无头历史消息正常消费。部署顺序是先加 nullable 列，再发布 Java，再发布 Bot。应用回退时保留新增列，不删除数据；历史记录用稳定兜底，不能称已恢复原 HTTP 编号。授权后提交范围为本任务文件，建议 `feat: persist correlation IDs across outbox and MQ`。

## Task 3：Bot 协程上下文、HTTP 写回和 Langfuse 关联

**消费 Task 2 的 MQ headers。产出接口：**

```python
# crosscutting/trace_context.py：纯逻辑，标准库依赖，无 Settings/外部 IO。
import hashlib
import re
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar

_trace_id: ContextVar[str | None] = ContextVar("trace_id", default=None)
_event_id: ContextVar[str | None] = ContextVar("event_id", default=None)

def resolve_trace_id(candidate: object, event_id: str | None = None) -> str:
    if isinstance(candidate, bytes) and len(candidate) <= 64:
        try:
            candidate = candidate.decode("ascii")
        except UnicodeDecodeError:
            candidate = None
    if isinstance(candidate, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", candidate):
        return candidate
    if isinstance(event_id, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", event_id):
        return hashlib.sha256(f"event:{event_id}".encode("utf-8")).hexdigest()[:32]
    return uuid.uuid4().hex

def current_trace_id() -> str | None:
    return _trace_id.get()

def current_event_id() -> str | None:
    return _event_id.get()

@contextmanager
def trace_scope(trace_id: str, event_id: str | None = None) -> Iterator[None]:
    trace_token = _trace_id.set(trace_id)
    event_token = _event_id.set(event_id)
    try:
        yield
    finally:
        _event_id.reset(event_token)
        _trace_id.reset(trace_token)
```

实际模块增加职责/边界注释和公开函数 docstring；调用 trace_scope 前确保 traceId/eventId 已校验。ContextVar 使用 token/reset 恢复，不能以共享普通全局变量保存当前值，也不能靠 threading.local 隔离 async 请求。Java resolveEvent 采用相同 UTF-8 哈希，不使用 UUID.nameUUIDFromBytes，避免跨语言结果不一致。

- [x] **先补现有行为测试。** test_consumer 的 StubMessage 新增可选 headers；用一个合法触发断言消费中的 currentTraceId、MainServiceClient 请求头、最终 RunTrace 同号，结束恢复。另用两个 asyncio 协程验证共享客户端不串号；非法/旧消息仍保持原 ack/failed 行为。test_tracing 在现有 stub 上断言新增 metadata，不访问真实 Langfuse。

```python
seen = []
async def one(trace_id: str) -> None:
    with trace_scope(trace_id, "event-1"):
        await client.get_json("/bot/comment/history")
async def two(trace_id: str) -> None:
    with trace_scope(trace_id, "event-2"):
        await client.post_json("/comment/send", payload)
await asyncio.gather(one("request-A"), two("request-B"))
assert {(request.method, request.headers["X-Request-Id"]) for request in seen} == {
    ("GET", "request-A"), ("POST", "request-B")
}
assert current_trace_id() is None
```

复用 test_main_service 的 httpx.MockTransport，其 handler 将 request 追加 seen 并返回合法 Result；payload 复用写回 fixture。测实际 httpx 请求，不断言自造 headers 字典。

- [x] **实现消费、写回及观测。** consumer._handle 在 body 校验之前读取安全 header 并建立 scope；header 缺失时解析合法 event.event_id 后用稳定兜底。毒丸异常只记错误类型/字段定位，不打印 Pydantic 完整输入；上下文覆盖 pipeline、兜底审计和 ack，finally/reset 包含异常与取消。不改变 kill 暂停或 ack 规则。

MainServiceClient.get_json/post_json 每次调用构造局部 headers，不能修改共享 AsyncClient.headers：

```python
trace_id = current_trace_id()
headers = {"X-Request-Id": trace_id} if trace_id else {}
resp = await self._http.post(path, json=payload, headers=headers)
```

现有 Authorization 默认头仍由 httpx 合并保留；适用于评论树、检索与写回等所有该客户端请求。

RunTrace 追加 `trace_id: str | None = None`、`event_id: str | None = None`，pipeline.run 从 current context 填充；不改 TriggerEvent JSON 契约、不新增 SQLite 列。Langfuse 保留 `create_trace_id(seed=f"run-{comment_id}")`，metadata 用 `business_trace_id` 保存业务编号，并记录 event_id；Bot 日志同时输出 `langfuseTraceId`。外部业务编号可能不是合法 32 hex，不能直接当 Langfuse ID。

pipeline 最终决策日志只带 decision/mode/commentId/postId、durationMs、有限错误分类；HTTPReplyWriter 成功记“写回提交”，不能记“审核通过”。现有 RunTrace 内容和成本计算不变。server 使用纯 ASGI middleware 关联 FastAPI 请求（避免 BaseHTTPMiddleware 的 ContextVar 传播限制），给响应加 X-Request-Id；不改变接口外壳。

logging_config 给 QueueHandler 上的 LogRecord filter 捕获 traceId/eventId；无来源填 `-`，确保后台线程不读错误上下文。QueueHandler 的 prepare 会处理异常信息，须确保最终 formatter 不重复附加堆栈；以一条真实异常日志验证堆栈仍在。生命周期启动/关闭由 server lifespan 管理，初始化在 runtime.start 前；用 try/finally 包围 runtime 初始化和 yield，启动失败也关闭自己的日志资源，正常关闭先停止业务再 drain/stop listener。只移除自己安装的 handler，重复 create_app 不能重复打印。uvicorn access 输出不另外写完整带 query 的 URL；应用自己的完成日志只记 path。

- [x] **定向验证一次。** `uv run pytest tests/unit/test_consumer.py tests/unit/infra/test_main_service.py tests/unit/infra/test_tracing.py tests/unit/infra/test_logging_config.py -q`。失败 stub 可使用 fake LLM；真实 HTTP 鉴权/写库另在 Task 4 验收，不用 MockTransport 结果冒充 E2E。

- [x] **检查隔离与契约。** ContextVar 不反向 import infra；共享客户端不串请求；Langfuse 故障保留当前“不阻断业务”行为；旧测试模型能使用默认空字段。授权后提交建议 `feat: correlate bot logs and main-service callbacks`。

## Task 4：日志落盘、关键日志补齐、一次链路验收与文档收口

**消费前 3 个任务的关联能力。产出：** 持久化日志、滚动/清理配置、可执行排查手册和真实验收记录。

- [x] **配置持久化与有界归档。** Java 优先复用 Spring Boot 默认 Logback 文件 appender，不新增自定义日志框架；application.yml 的现有级别配置保留，补以下内容（整合进现有 logging 节点，不能创建重复节点）：

```yaml
logging:
  file:
    name: ${QUANTA_LOG_FILE:logs/demo0.log}
  pattern:
    console: "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC} %-5level service=demo0 thread=%thread traceId=%X{traceId:-} eventId=%X{eventId:-} logger=%logger{36} %msg%n"
    file: "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC} %-5level service=demo0 thread=%thread traceId=%X{traceId:-} eventId=%X{eventId:-} logger=%logger{36} %msg%n"
  logback:
    rollingpolicy:
      file-name-pattern: ${QUANTA_LOG_FILE:logs/demo0.log}.%d{yyyy-MM-dd,UTC}.%i.gz
      max-file-size: ${QUANTA_LOG_MAX_FILE_SIZE:20MB}
      max-history: ${QUANTA_LOG_MAX_HISTORY:14}
      total-size-cap: ${QUANTA_LOG_TOTAL_SIZE_CAP:300MB}
      clean-history-on-start: true
```

Python Settings 增加 `log_file="data/logs/quantabot.log"`、`log_level="INFO"`、`log_max_bytes=20*1024*1024`、`log_retention_days=14`、`log_total_size_bytes=300*1024*1024`，正数校验；空 log_file 明确关闭文件输出、保留控制台。logging_config 使用小型 SizeAndTimeRotatingFileHandler，基于标准库 Handler 实现 UTC 日切与大小触发；选择组合实现以统一 gzip 归档、重启后的日期检测和容量清理。归档名包含日期时间和递增序号，同一秒多次滚动/重启不覆盖历史。只匹配本服务明确归档文件名，按日期/总量清理，不递归删目录、不碰活动文件/SQLite。

每次滚动和启动执行归档清理；无新日志时不单独起清理定时任务。文件不可写/运行期写失败保留控制台并打印明确的脱敏错误，不把文件保存标为健康；正常 shutdown 排空 QueueListener。测试使用临时目录、缩小字节阈值、模拟日期与显式关闭，不 sleep 到次日、不写大文件。一个 handler 测试覆盖追加、滚动、不覆盖旧文件和清理，只测承诺的结果。

根 .gitignore 仅添加 `demo0/logs/`、`QuantaBot/data/logs/`（后者已有 data 忽略仍可明确说明），防 .gz 归档误提交；现有 `*.log` 无法覆盖 .gz。环境模板只写非敏感目录/容量参数。compose 复用已有 data 挂载，不改用户现有环境/端口/镜像设置。

- [x] **补齐日志并作小型运行验证。** CommentCommandServiceImpl 注册 afterCommit 留痕，覆盖外层提交幂等事务；不能在未提交事务内部打印“已持久化成功”。Appender 事务内日志写“Outbox已追加，提交取决于事务”；Dispatcher 已有 SENT 日志才表示已投递。CommentAuditServiceImpl 在 afterCommit 记录审核状态。附带修改 `demo0/src/main/java/com/quanta/demo0/comment/controller/user/CommentController.java` 原有整 DTO 日志，仅保留 contentId/parentId，防止持久化评论正文。此处日志文案/配置按 AGENTS 的非行为降档，不为文字另写测试；编译及链路验证检验实际输出。检查 HTTP 异常、消费异常日志有堆栈，已知正常业务拒绝使用有限原因不重复打印大量堆栈。

本地正常启动两服务，各触发一条已有接口/业务日志；确认文件出现、UTF-8 中文/traceId 可读、正常重启后追加而非覆盖。用测试配置小阈值触发一次归档，生产默认值恢复。一次 ruff/单元总回归作为 Bot 规范收口：`uv run ruff format --check src tests`、`uv run ruff check src tests`、`uv run pytest tests/unit -q`；只格式化本次修改的文件。Java 使用前面定向测试及编译，不追加全量压测/全 API 重跑。

- [x] **一次真实边界联调，正常与受控失败各一例。** 以 `demo0/docs/api-test/cases/07-bot.http` B2 为基线，临时带 `X-Request-Id: trace-check-20261005-A`，使用真实用户/Bot 身份、真实 MySQL/Outbox/RabbitMQ/主服务写库与审核。允许只替换 LLM 决策/生成以控制成本，必须明确标“fake LLM 边界验收”；真实 Langfuse 查询只核对一次 business_trace_id，不跑 Persona/红队。

成功例逐项核对：响应头 A → 评论业务结果 → 数据库 Outbox trace_id=A → MQ headers A → Bot 文件日志 A → Java 写回请求 A → 二次审核最终可见。不能只看 HTTP 200。受控失败例只让 LLM 适配器抛超时，确认 Bot 失败/不回日志同号、没有新回复、原评论状态正确；不为了日志功能注入额外审核违规案例，不修改业务策略。

日志排查命令写入手册（从根目录运行；真实部署目录按环境配置替换）：

```powershell
rg -n -F 'traceId=trace-check-20261005-A' demo0/logs QuantaBot/data/logs -g '*.log'
```

gzip 历史归档在只读工具中解压后搜索，不把二进制压缩文件交给普通 rg 当文本扫描。手册列出本地绝对路径、UTC 与北京时间转换、启动工作目录/可写权限、单进程限制、编号/事件编号区别、历史无号局限、容器挂载和应用回滚办法。

依赖不可用写 BLOCKED/PARTIAL，保留已有 RESULTS，不覆盖旧验收或宣称生产部署完成。只执行受影响的 B2 请求，不重跑 17 个 Bot 人格场景；既有发布门禁保持原职责。

- [x] **文档与审查收口。** 修改总方案第 10 项链接本计划及手册，QuantaBot 技术选型增加日志关联边界，总计划记录 M5 后工程维护项；RESULTS 新增本轮单独章节，只填写实际命令/环境/状态。检查 docs 链接、迁移顺序、旧消息兼容和变更文件列表。实施完成后按 AGENTS 做一次独立审查；数据库/MQ/安全新增关键断言做最小人工变异抽查，不扩大为全局测试工程。敏感改动仅 `.gitignore` 的两个日志路径应在交付中单列；不改 pom/CI。

授权后本任务建议提交 `feat: persist correlated logs with bounded retention`；未经授权不提交/合并/push。

## 验收清单与测试预算

- [ ] 公开/受保护请求都有响应编号；正常、401/403、异常后的 MDC 生命周期正确，安全与 Result 契约不变。
- [ ] Outbox 从数据库恢复编号；重复插入、投递重试、死信重投和人工重放保持事件身份/编号；旧消息仍可处理。
- [ ] Java 两处线程池调用和 Bot 两个并发协程上下文隔离；Bot 写回及 Langfuse metadata 能连接原操作。
- [ ] Java/Bot 文件持续追加，正常重启保留历史；日期/大小滚动、压缩与限额清理可复核。
- [ ] 正常/受控失败链路各一次有真实边界证据，最终业务结果与日志一致；无敏感 payload/凭据新增。

预算口径：3 组定向行为验证 + 1 个临时目录归档行为测试 + 1 次日志文件 smoke + 正常/失败各 1 次 E2E，Bot 全单元收口仅一次。TDD 每组首次 RED 和实现后 GREEN 是必要节拍，归档行为测试同样先 RED 后实现；不按每个字段拆测试、不重复给 9 个消费者测相同上下文、不因日志文案改动跑人格评测，不做性能提升数字。失败或新改动时只重跑受影响组。

## 兼容、回滚和风险

1. MySQL 为 additive nullable 迁移；新代码依赖新列，不能先发应用后迁移。旧应用/旧 Bot 会忽略额外消息头；旧数据稳定兜底，不声称还原历史用户请求。
2. 回滚应用时保留 trace_id 列、现有日志和 SQLite；新增文件输出可通过环境配置关闭/改目录。不得为回退删除历史文件或回滚业务状态。
3. 归档限额会使高流量下不足 14 天，14 天是上限不是保证；生产目录容量与备份由实际部署决定。
4. traceId 不是访问凭证，任何日志平台仍需权限；本次不搭平台，也不自动生成调用拓扑。
5. 日志不是事务事实源；不能为“日志已打印”宣称数据库已提交、消息已消费或审核通过。
6. QueueListener 正常排空不等于断电不丢；当前单进程/有限日志量方案不承诺审计级运行日志零丢失。

## 执行证据说明

实现和边界验收结果已记入 S-TRACE；独立审查未发现已确认的阻塞问题。前三个实现子代理因用量限制中断，未取得它们完整的首次 RED 记录，因此 Task 1/2 的首次失败证据步骤不追认完成。协调者已核对全部实现并实际执行定向 GREEN、真实链路与归档 smoke；Bot 500 响应头缺失有明确 RED/GREEN 证据。本次未扩大为全局变异测试，也未实际重放生产死信。

## 自查与继续方式

本计划覆盖请求入口、MDC、审计复用、异步传播、Bot 写回/Langfuse、关键日志、持续落盘/归档及按编号排查；没有待用户裁决的业务分支。日志容量/期限使用上表可配置默认值，不当作已验证的生产配置。前端复制编号、集中日志平台与 span 追踪留在本期范围外。

Task 1–4 已实施，后续发布按 S-TRACE 和排查手册的迁移顺序执行；共享上下文/字段签名以本文件为准。

技术参考（仅补充机制，以项目实际版本/代码为准）：[Spring Boot 3.5 日志与滚动配置](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html)、[Spring AMQP listener advice-chain](https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html)、[Python 3.12 logging handlers](https://docs.python.org/3.12/library/logging.handlers.html)、[Python 3.12 ContextVar 日志上下文](https://docs.python.org/3.12/howto/logging-cookbook.html#use-of-contextvars)。
