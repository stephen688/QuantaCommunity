"""infra/tracing —— RunTracer 端口实现（Langfuse 上报；NullTracer 降级）。

职责：把 RunTrace 上报为 Langfuse trace（决策侧）+ generation（生成侧，含 token/成本 metadata）。
边界：观测边界——Langfuse 任何故障 WARNING 留痕、绝不抛出（静默优于乱回的观测侧表达）；
      SDK 调用为后台批量入队（官方 SDK 线程模型，不阻塞事件循环）；shutdown 时 flush；
      usage 走 metadata 而非 SDK Usage 类型（避免 SDK 版本耦合，M5 观测升级再接官方字段）。
说明：SDK 为 langfuse 4.x（v4 服务端配套，ingestion v4 实时可见）。v4 数据模型下
      trace 级 input/output 已废弃，由根 observation 承载；trace_id 须为 32 位 hex，
      以官方 create_trace_id(seed="run-{comment_id}") 确定性映射（外部 id 可反查）。
"""

import logging
from typing import Any

from langfuse import Langfuse

from quanta_bot.pipeline.ports import RunTrace

logger = logging.getLogger(__name__)


class NullTracer:
    """空实现（fake_mode / Langfuse 未配置时降级）。"""

    async def record(self, trace: RunTrace) -> None:
        return None


class LangfuseTracer:
    """Langfuse 真实现（client 参数供单测注入 stub，生产缺省自建）。"""

    def __init__(
        self,
        public_key: str,
        secret_key: str,
        host: str,
        client: Any | None = None,
    ) -> None:
        self._langfuse = client or Langfuse(public_key=public_key, secret_key=secret_key, host=host)

    async def record(self, trace: RunTrace) -> None:
        """上报 trace（根 observation）+（若进生成阶段）generation；一切异常兜底为 WARNING。"""
        try:
            trace_id = self._langfuse.create_trace_id(seed=f"run-{trace.comment_id}")
            # v4：根 observation 承载 trace 语义（决策结论 + 全量 metadata）
            with self._langfuse.start_as_current_observation(
                trace_context={"trace_id": trace_id},
                name="pipeline.run",
                as_type="span",
                input=trace.trigger_content,
                output=f"{trace.decision}: {trace.reason}",
                metadata=trace.model_dump(mode="json"),
                end_on_exit=True,
            ) as root:
                if trace.generated_content is not None:
                    gen = root.start_observation(
                        name="generation",
                        as_type="generation",
                        input=trace.context_text,
                        output=trace.generated_content,
                        metadata={
                            "prompt_tokens": trace.prompt_tokens,
                            "completion_tokens": trace.completion_tokens,
                            "cost_li": trace.cost_li,
                            "daily_cost_li_after": trace.daily_cost_li_after,
                            "mode": trace.mode,
                        },
                    )
                    gen.end()
        except Exception as exc:  # 观测边界：Langfuse 故障不得阻断业务（WARNING 留痕）
            logger.warning("Langfuse 上报失败（已忽略，业务不受影响）：%s", exc)

    def flush(self) -> None:
        """强制清空上报队列（shutdown / integration 验证用）。"""
        try:
            self._langfuse.flush()
        except Exception as exc:
            logger.warning("Langfuse flush 失败（已忽略）：%s", exc)

    async def aclose(self) -> None:
        """收尾：flush 后关闭（composition 统一调用）。"""
        self.flush()
