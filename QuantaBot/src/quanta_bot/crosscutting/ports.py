"""crosscutting/ports —— 横切层消费的端口（协议）定义。

职责：定义 KeyValueStore（幂等/开关/成本键的存储语义）与 DecisionAudit（决策日志）端口，
      供 crosscutting 与 pipeline（正向 import）依赖；infra 提供实现，composition 装配注入。
边界：本文件只含协议与端口数据类型，零实现（crosscutting 不 import infra，AGENTS.md §4.1）。
      pipeline 消费的端口（写库/评论树）在 pipeline/ports.py，勿混放。
"""

from datetime import UTC, datetime
from typing import Literal, Protocol

from pydantic import BaseModel, Field

# 决策结果枚举（诚实口径：为什么回/为什么没回同样完整，PRD F6）
Decision = Literal[
    "replied",  # 已回复（走完生成+写库）
    "skipped_not_mentioned",  # 未命中 @，链路未进入
    "skipped_idempotent",  # 重复投递，幂等拦截
    "rejected_moderation",  # 规则预检拦截（红线：违规不出）
    "failed",  # 链路异常（静默不回，决策日志留痕）
]


class DecisionLogEntry(BaseModel):
    """决策日志条目（SQLite 明细表结构；Langfuse trace 字段子集，M2 上报）。"""

    comment_id: int
    decision: Decision
    mode: str | None = Field(default=None, description="表达模式；未进决策/生成阶段为 None")
    reason: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))


class KeyValueStore(Protocol):
    """kv 存储（Redis 语义抽象；M1 内存 fake，M2 真 Redis，端口不变）。"""

    async def set_if_absent(self, key: str, ttl_seconds: int) -> bool:
        """SETNX+TTL 语义：键不存在（或已过期）则设置并返回 True；否则 False。"""
        ...


class DecisionAudit(Protocol):
    """决策日志审计端口（M1：SQLite 明细；M2：+Langfuse 上报）。"""

    async def record(self, entry: DecisionLogEntry) -> None:
        """落一条决策明细（何回/何不回/何降级——统计口径诚实）。"""
        ...
