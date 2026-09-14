"""composition —— 装配根（全仓库唯一可 import 一切的模块，AGENTS.md §4.1 例外）。

职责：读 Settings → 构建 kv/audit/reply_writer 实现（fake_mode 切换）→ 组装 PipelineDeps。
边界：不含业务逻辑；server/consumer（M2）只经它拿依赖；未实现的真依赖宁可炸也不静默降级。
"""

from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeReplyWriter
from quanta_bot.infra.settings import Settings
from quanta_bot.pipeline.pipeline import PipelineDeps


def build_pipeline_deps(settings: Settings) -> PipelineDeps:
    """装配管线依赖：audit 恒为真 SQLite；kv/写库按 fake_mode 切换（M2 起补真实现）。"""
    audit = SQLiteAudit(settings.audit_db_path)
    if settings.fake_mode:
        return PipelineDeps(kv=InMemoryKV(), audit=audit, reply_writer=FakeReplyWriter())
    # 诚实失败：真实现随 M2 逐项落地，在此之前显式炸而非静默假装可用
    raise NotImplementedError("M2 前仅支持 fake_mode=True（真kv/写库客户端随 M2 落地）")
