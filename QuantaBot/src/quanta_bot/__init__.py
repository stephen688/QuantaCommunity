"""quanta_bot —— QuantaCommunity AI 评论智能体服务主体。

分层与依赖方向（硬规则，禁止反向 import）：
- pipeline/      核心链路：触发 → 决策 → 上下文 → 生成（业务，常改）
- memory/        记忆层：接口在此，实现走 infra
- crosscutting/  横切纪律：幂等/熔断/频率/预算/开关/审核（全纯逻辑）
- infra/         唯一触碰外部世界的层（settings/mq/vector/kv/audit_db/tracing）
"""

__version__ = "0.1.0"
