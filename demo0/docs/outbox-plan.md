# Outbox 可靠事件链路改造计划

## 一、目标与已确定方案

将当前“数据库提交后直接发送 MQ、更新 Redis/ES”的方式，改造成可重试、可追踪、可人工恢复的可靠事件链路。

已确定：

- 第一版先改造“帖子发布 → AI 审核 → 审核结果通知”；
- 后续版本覆盖回答、评论、Feed、热度和 ES；向量库后续单独优化，不纳入本次 Outbox 改造；
- 投递语义采用“至少一次”，允许消息重复到达，但业务结果只能生效一次；
- 使用 Spring 定时轮询，不引入 Kafka、Debezium、Redisson；
- 轮询器支持多个后端实例同时运行；
- 消费者使用统一 Inbox 表防止重复消费；
- 管理端提供失败事件查询、详情和人工重放；
- 点赞、收藏等普通 Redis 缓存刷新暂不进入 Outbox，继续采用 MySQL 为准、缓存可重建的方式。
- WebSocket 实时推送属于尽力而为的展示能力，不作为 Outbox 可靠投递的一部分，通知数据库记录才是最终依据。

## 二、Outbox 与 Inbox 基础设计

### 1. Outbox 表

新增 `tb_outbox_event`，主要字段：

- `event_id`：UUID，事件唯一标识；
- `event_type`：事件类型；
- `aggregate_type`、`aggregate_id`：关联的业务对象；
- `payload`：投递所需的最小 JSON 消息；
- `status`：`PENDING / PROCESSING / SENT / DEAD`；
- `retry_count`、`next_retry_time`：重试控制；
- `locked_by`、`locked_until`：多实例抢占、租约恢复和所有权校验；
- `last_error`：最后一次失败原因；
- `replay_count`、`last_replay_by`、`last_replay_time`：人工重放记录；
- `create_time`、`update_time`、`sent_time`。

建立以下索引：

- `event_id` 唯一索引；
- `(status, next_retry_time, create_time)` 扫描索引；
- `(status, locked_until, create_time)` 租约回收索引；
- `(aggregate_type, aggregate_id)` 业务查询索引。

业务数据和 Outbox 事件必须在同一个 MySQL 事务内保存。业务事务回滚时，事件也必须一起回滚。

V1 的审核事件只保存审核必需的目标类型、目标 ID、发布者 ID、标题、正文和图片 URL，不序列化完整业务实体。当前帖子正文最多 500 字、图片最多 5 张，因此“单条消息达到几百 KB”并不符合现有代码；但仍增加 UTF-8 序列化后 32 KB 的硬上限，并限制单个图片 URL 长度。超过上限时直接拒绝发布并回滚业务事务，不能留下一个永远无法投递的待审核帖子。

### 2. Inbox 表

新增 `tb_inbox_event`，用于记录每个消费者处理事件的结果：

- `event_id`、`consumer_name`；
- `event_type`、业务对象信息和对应的 `outbox_event_id`；
- `status`：`PROCESSING / RETRYING / SUCCESS / DEAD`；
- `retry_count`、`locked_by`、`locked_until`、`last_error`；
- 人工重放信息和处理时间。

使用 `(consumer_name, event_id)` 联合唯一索引。同一个事件可以被多个消费者处理，但同一个消费者只能成功处理一次。

Inbox 不重复保存 payload，详情和重放时通过 `event_id` 读取 Outbox 原消息。清理 Outbox 时，存在 `PROCESSING / RETRYING / DEAD` Inbox 记录的事件不得删除。

### 3. 消息公共字段

现有 MQ 消息 DTO 逐步增加：

- `eventId`；
- `eventType`；
- `occurredAt`。

事件类型统一由枚举维护，并映射到现有 RabbitMQ 交换机、路由键和消息类型。迁移期间保留原有队列，不重新搭建整套 MQ 拓扑。

### 4. Outbox 发送器

默认参数：

- 每 1 秒扫描一次；
- 每批最多 50 条；
- 事件租约 60 秒；
- RabbitMQ 发布确认等待 5 秒；
- RabbitTemplate 开启 `mandatory=true`，确保无法路由的消息进入 Return 处理；
- 最多失败 6 次；
- 重试间隔依次为 5 秒、15 秒、1 分钟、5 分钟、15 分钟、30 分钟。

处理过程：

1. 使用 `FOR UPDATE SKIP LOCKED` 抢占到期的 `PENDING` 事件，以及租约已过期的 `PROCESSING` 事件；
2. 在短事务中标记为 `PROCESSING` 并写入当前实例标识；
3. 提交事务后发送 RabbitMQ，避免发送期间长期占用数据库锁；
4. 使用 `CorrelationData(eventId)` 将 Confirm 和 Return 结果关联到同一条 Outbox 记录；
5. 只有 Confirm 为 ACK、没有 Return 且未超时，才标记为 `SENT`；
6. 失败后计算下次重试时间，超过次数标记 `DEAD`；
7. Confirm 为 ACK 但收到 Return，说明交换机收到消息却没有队列承接，必须按发送失败处理，并记录“消息未路由”；
8. Confirm 为 NACK、等待超时或发送异常都按失败处理；
9. 如果 RabbitMQ 已收到消息，但程序在标记 `SENT` 前宕机，事件会再次发送，由 Inbox 保证业务不重复；
10. 标记 `SENT`、重试或 `DEAD` 时必须同时匹配 `status=PROCESSING` 和当前 `locked_by`，防止租约过期后旧实例覆盖新实例结果。

`locked_by` 不另建表，它除了排查价值，还承担所有权令牌的作用。即使旧实例在网络卡顿后恢复，也不能更新已经被其他实例接管的事件。

### 5. 消费者统一处理

新增统一的 Inbox 执行模板：

1. 收到消息后按 `consumerName + eventId` 尝试登记，并设置 60 秒处理租约；
2. 已经是 `SUCCESS` 时直接 ACK；
3. `PROCESSING` 且租约未过期时不重复执行，消息进入重试队列；
4. `PROCESSING` 但租约已过期时，使用带旧状态和旧租约条件的更新重新抢占，只有影响一行的实例可以继续处理；
5. RabbitMQ 连接断开时，未 ACK 消息会重新入队；重新投递后由上述过期租约抢占完成宕机恢复，不需要把 Inbox 永久卡在 `PROCESSING`；
6. 业务成功后将 Inbox 标记为 `SUCCESS`，提交成功后再 ACK；
7. 更新 Inbox 结果时同样校验 `locked_by`，旧实例不能覆盖接管者的处理结果；
8. 失败时使用独立事务记录错误和次数，避免错误记录随业务事务一起回滚；
9. 消费失败最多重试 3 次，之后进入 DLQ，同时将 Inbox 标记为 `DEAD`。

通知落库和 Inbox 成功状态使用同一个 MySQL 事务。Redis、ES 等外部系统使用覆盖式写入、删除或重新计算，保证重复执行不会改变最终结果。

WebSocket 推送由独立的轻量推送器执行，不参与 Inbox 事务、不持有数据库连接、不影响 ACK，失败只记录日志。即使应用在通知落库后、推送前宕机，用户仍可从通知列表读取数据库中的记录。

## 三、分版本实施路线

### V1：Outbox 基础能力与帖子审核链路

改造范围：

- 建立 Outbox、Inbox 表和对应实体、Mapper、Service；
- 增加多实例安全轮询器、路由注册表、发布确认和失败重试；
- 帖子发布事务不再通过 `afterCommit` 直接发送审核 MQ，而是在事务内写入 `MODERATION_REQUESTED`；
- `MODERATION_REQUESTED` payload 只保留审核所需字段并执行 32 KB 大小校验；
- V1 仅迁移 `ModerationTargetType.CONTENT`，回答和评论放到 V2；
- 审核消费者处理前检查帖子是否仍为待审核，已处理则直接幂等成功；
- 审核服务增加明确事务，审核状态使用条件更新，只允许 `PENDING` 变为最终状态；
- 审核状态真正变化时，在同一事务写入 `NOTIFICATION_REQUESTED`；
- 通知消费者使用 Inbox 防重，通知落库成功后再 ACK；
- WebSocket 推送交给事务外的尽力而为推送器，不能回滚通知和 Inbox 状态；
- 为通知队列补充重试队列和死信队列；
- 建立管理端“事件中心”，支持 Outbox、Inbox 查询和重放；
- V1 即提供 `PENDING`、`PROCESSING`、`DEAD` 数量接口、管理端红点和定时错误日志；平均延迟、重试分布等详细指标留到 V4。

V1 完成标准：

- RabbitMQ 不可用时，帖子和 Outbox 事件仍然能够同时保存；
- RabbitMQ 恢复后审核任务可以自动补发；
- 重复发送同一审核事件，不会重复修改审核状态或重复生成通知；
- Inbox 消费实例宕机后，未 ACK 消息重新投递，并在租约到期后由其他实例接管；
- 帖子发布路径中原有 `afterCommit → moderationProducer.sendModerationTask` 代码已经删除，静态搜索不能再找到这条直接发送链路，避免 Outbox 与旧逻辑双通道发送。

### V2：回答、评论审核与全部通知

- 将回答发布、评论发布产生的审核任务迁移到 `MODERATION_REQUESTED`；
- 所有通知生产位置改为事务内写入 `NOTIFICATION_REQUESTED`；
- 点赞、评论、回复、关注、采纳、举报处理、身份审核等通知统一经过 Outbox；
- 所有通知消费者共用 Inbox 防重；
- 清理已经迁移完成的直接 `NotificationProducer` 调用和对应 `afterCommit`；
- 保持旧消息 DTO 临时兼容空 `eventId`，待旧队列排空后拒绝无 `eventId` 的新消息。

### V3：Feed 与热度更新

新增并迁移：

- `FEED_UPSERT_REQUESTED`；
- `FEED_DELETE_REQUESTED`；
- `HOT_SCORE_RECALCULATE_REQUESTED`。

处理原则：

- Feed 消费者处理前读取内容当前状态，已删除或未审核通过的内容不能重新加入 Feed；
- Feed 使用确定的内容 ID 作为 Redis 成员，重复添加和删除不产生副作用；
- 热度事件只携带内容 ID，消费者根据 MySQL 当前点赞、收藏、评论数据重新计算，而不是直接累加消息中的增量；
- 为 Feed 删除和热度队列补齐统一重试与 DLQ；
- 清理对应直接 MQ 调用和 `afterCommit`。

### V4：ES 可靠同步与最终收口

新增：

- `SEARCH_RECONCILE_REQUESTED`。

消费者收到事件后读取 MySQL 当前状态：

- 内容存在、审核通过且未删除：执行 upsert；
- 内容不存在、被驳回或已删除：执行 delete。

这样即使更新、审核和删除事件乱序到达，最终 ES 索引状态仍以 MySQL 当前数据为准。向量存储及其同步方式不在本计划中调整，后续另行设计和验收。

最后完成：

- 迁移帖子、回答、评论审核和删除产生的所有 ES 同步；
- 删除已被 Outbox 替代的直接外部调用和无效 `afterCommit`；
- 每天清理 30 天前的 `SENT` 和 `SUCCESS` 记录；
- `DEAD` 事件长期保留，直到人工重放成功或管理员确认关闭；
- 在 V1 基础计数之上增加平均发送延迟、重试次数分布和最长积压时间。

## 四、管理端接口与页面

新增接口：

- `GET /admin/events/outbox/page`：分页查询 Outbox；
- `GET /admin/events/outbox/{eventId}`：查看事件和 payload；
- `POST /admin/events/outbox/{eventId}/replay`：重放死亡事件；
- `GET /admin/events/inbox/page`：分页查询消费记录；
- `GET /admin/events/inbox/{consumerName}/{eventId}`：查看消费详情；
- `POST /admin/events/inbox/{consumerName}/{eventId}/replay`：重放消费失败事件。

支持按状态、事件类型、业务类型、业务 ID、事件 ID 和时间范围筛选。

Inbox 重放时：

- 只允许当前状态为 `DEAD` 的 Inbox 记录重放；
- 使用 `WHERE status = 'DEAD'` 的条件更新抢占重放权，两个管理员同时操作时只能有一个请求影响一行；
- 将 Inbox 状态重置为可处理，并通过 `eventId` 从 Outbox 读取原 payload；
- 将原 Outbox 记录重置为 `PENDING`，继续使用原来的 `eventId`；
- 记录管理员 ID、重放时间和重放次数；
- 不允许重放正在处理中的事件。

Outbox 重放同样只允许 `DEAD`，并通过条件更新保证并发安全。V1 不开放普通 `SENT` 事件直接重放：相同 `eventId` 会被已成功的 Inbox 拦截，换新 `eventId` 又可能重复产生业务结果。消费者处理失败但 Outbox 已是 `SENT` 时，应从对应的 Inbox `DEAD` 记录发起重放。若以后确实需要强制修复已成功事件，单独设计带原因、指定消费者和完整审计记录的强制操作。

管理端新增 `/events` 页面，包含 Outbox、Inbox 两个页签、详情抽屉和重放确认框，继续使用现有管理员 JWT 权限体系，不在本阶段迁移 Spring Security。

## 五、测试与验收

增加单元测试和基于 MySQL 8、RabbitMQ 的集成测试：

- 业务事务回滚时 Outbox 不留下事件；
- 两个发送器实例不能同时成功抢占同一条记录；
- RabbitMQ 停止后事件进入重试，恢复后能够发送；
- MQ 已确认但数据库未标记成功时，重复发送不会重复产生业务结果；
- Confirm ACK 但消息被 Return 时，Outbox 不得标记为 `SENT`；
- 同一审核事件重复消费，只调用一次有效状态变更；
- 同一通知事件重复消费，数据库只有一条通知；
- Outbox 和 Inbox 的处理实例宕机后，租约到期都可以由其他实例接管；
- 旧实例在租约失效后恢复时，不能覆盖新实例写入的状态；
- 超过重试次数后正确进入 `DEAD` 和 DLQ；
- 两个管理员同时重放同一事件时，只有一个请求成功改变状态；
- 审核 payload 超过 32 KB 时，帖子发布事务整体回滚；
- 管理员重放后事件重新处理，并保留操作记录；
- ES、Feed 和热度事件乱序到达后，最终结果与 MySQL 一致。

每个版本都必须完成编译、Mapper XML 解析、自动化测试和 RabbitMQ 断连恢复演练后，才能迁移下一批业务。

## 六、前提与边界

- 目标数据库为 MySQL 8.0，使用 `SKIP LOCKED`；实际实施前先执行只读版本检查，不满足则先升级数据库；
- 不引入 Kafka、Debezium、分布式事务或“恰好一次”承诺；
- RabbitMQ 发布失败和消费失败分别管理，不能用 Outbox 重试代替消费者幂等；
- Outbox payload 不保存密码、Token、云服务密钥或完整异常堆栈；
- Outbox 的 `locked_by` 是租约所有权令牌，不是额外基础设施，所有状态完成操作都必须校验该字段；
- Inbox 不复制 Outbox payload，重放依赖原 Outbox 记录，因此存在未成功 Inbox 记录的 Outbox 事件禁止清理；
- 向量库及本地 JSON 持久化不属于本次改造范围，相关优化单独制定计划；
- 第一阶段尚未完成的回答点赞、评论点赞和并发测试应先收尾；Outbox 不负责修复点赞本身的并发问题。
