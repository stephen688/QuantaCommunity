"""composition —— 装配根（全仓库唯一可 import 一切的模块，AGENTS.md §4.1 例外）。

职责：读 Settings → 按依赖逐项构建实现（fake_mode 总开关 + 缺配置即降级）→ 组装 Runtime。
边界：不含业务逻辑；真模式降级路径全部 WARNING 留痕；写库无降级——main_service 未配置用诚实占位
      （UnimplementedReplyWriter，调用即 failed），绝不静默假装。
"""

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import DeepSeekClient, FakeLLM
from quanta_bot.infra.kv import InMemoryKV, RedisKV
from quanta_bot.infra.main_service import (
    FakeCommentTreeFetcher,
    FakeReplyWriter,
    HTTPCommentTreeFetcher,
    MainServiceClient,
    UnimplementedReplyWriter,
)
from quanta_bot.infra.settings import Settings, resolve_data_path
from quanta_bot.infra.tracing import LangfuseTracer, NullTracer
from quanta_bot.pipeline.pipeline import PipelineDeps

logger = logging.getLogger(__name__)


@dataclass
class Runtime:
    """运行时包（deps + 资源回收 + 控制面启停；server lifespan 托管）。"""

    deps: PipelineDeps
    settings: Settings
    control_plane: ControlPlane
    _closers: list[Callable[[], Awaitable[None]]] = field(default_factory=list)

    def start(self) -> None:
        """启动后台任务（真模式起控制面轮询；fake 模式快照静态不轮询）。"""
        if not self.settings.fake_mode:
            self.control_plane.start_polling()

    async def aclose(self) -> None:
        """收尾：停轮询 + 逐项释放客户端（幂等）。"""
        self.control_plane.stop_polling()
        for closer in self._closers:
            try:
                await closer()
            except Exception as exc:  # 收尾边界：单项清理失败不阻断其余项
                logger.warning("运行时收尾单项失败（继续清理其余项）：%s", exc)


def build_runtime(settings: Settings) -> Runtime:
    """装配运行时：audit 恒为真 SQLite；其余按 fake_mode/配置逐项组装。"""
    audit = SQLiteAudit(str(resolve_data_path(settings.audit_db_path)))
    closers: list[Callable[[], Awaitable[None]]] = []

    if settings.fake_mode:
        kv: InMemoryKV | RedisKV = InMemoryKV()
        llm = FakeLLM()
        tracer = NullTracer()
        writer = FakeReplyWriter()
        tree = FakeCommentTreeFetcher()
    else:
        # ---- 真模式：逐项真接，缺配置即降级（技术选型「空值即关闭/降级」）----
        if settings.redis_url:
            kv = RedisKV(settings.redis_url, settings.redis_timeout_seconds)
            closers.append(kv.aclose)
        else:
            logger.warning("redis_url 未配置——kv 降级为内存版（幂等仅进程内，重启即失）")
            kv = InMemoryKV()
        if settings.deepseek_api_key:
            llm = DeepSeekClient(
                settings.deepseek_base_url,
                settings.deepseek_api_key,
                settings.deepseek_model,
                settings.llm_timeout_seconds,
            )
            closers.append(llm.aclose)
        else:
            logger.warning("deepseek_api_key 未配置——LLM 降级为 FakeLLM（固定文本）")
            llm = FakeLLM()
        if settings.langfuse_public_key and settings.langfuse_secret_key:
            tracer = LangfuseTracer(
                settings.langfuse_public_key, settings.langfuse_secret_key, settings.langfuse_host
            )
            closers.append(tracer.aclose)
        else:
            logger.warning("langfuse 密钥未配置——tracing 降级为 NullTracer（不上报）")
            tracer = NullTracer()
        # 写库无降级（红线 §0.5）：main_service 未配置 → 诚实占位，调用即 failed（Task 16 换 HTTPReplyWriter）
        writer = UnimplementedReplyWriter()
        # 评论树（C-2）：main_service 配置齐 → 真客户端（main_service 暂存供 Task 16 复用同一 client）；
        # 缺配置 → fake 降级 WARNING 留痕
        if settings.main_service_base_url and settings.main_service_token:
            main_service = MainServiceClient(
                settings.main_service_base_url,
                settings.main_service_token,
                settings.main_service_timeout_seconds,
            )
            closers.append(main_service.aclose)
            tree = HTTPCommentTreeFetcher(main_service, settings.main_service_bot_user_id)
        else:
            logger.warning(
                "main_service 未配置——评论树降级为 fake（[C-2] 真接口随 demo0 D5 落地联调）"
            )
            tree = FakeCommentTreeFetcher()

    control_plane = ControlPlane(kv, poll_seconds=settings.control_plane_poll_seconds)
    deps = PipelineDeps(
        kv=kv,
        audit=audit,
        reply_writer=writer,
        llm=llm,
        tracer=tracer,
        control_plane=control_plane,
        comment_tree=tree,
        llm_input_price_per_mtok=settings.llm_input_price_per_mtok,
        llm_output_price_per_mtok=settings.llm_output_price_per_mtok,
        cost_key_ttl_hours=settings.cost_key_ttl_hours,
    )
    return Runtime(deps=deps, settings=settings, control_plane=control_plane, _closers=closers)
