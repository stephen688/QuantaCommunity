"""crosscutting/leak_scan —— 输出泄漏扫描（第三道审核，写库前；Globex §5.2 吸取）。

职责：扫描 LLM 输出中的内部信息泄漏（API key 形态/Bearer 串/内部地址/控制面键名/
      基础设施名与模型自报实现细节），命中替换 [已脱敏] + hits 留痕。
边界：绝不阻断回复链路（误伤突兀远轻于泄漏，但整轮失败不可接受——grill 决议 10）；
      判据刻意收窄（只扫"用户无需知道且泄露有害"的形态，不用宽泛正则误伤正常措辞）；
      main_service_base_url 等运行时值由 composition 以 extra_patterns 注入。
"""

import re
from collections.abc import Sequence

from pydantic import BaseModel

# 五类判据（蓝图 §5.2 + 总计划 M3-防幻觉护栏；顺序即报告顺序）
LEAK_PATTERN_SPECS: tuple[tuple[str, str], ...] = (
    ("api_key", r"sk-[A-Za-z0-9]{16,}"),
    ("bearer_token", r"Bearer\s+[A-Za-z0-9._\-]{16,}"),
    (
        "internal_url",
        r"https?://(?:127\.0\.0\.1|localhost|0\.0\.0\.0)(?::\d+)?[^\s，。]*",
    ),
    ("control_plane_key", r"quantabot:[a-z0-9:_\-]+"),
    (
        "infra_or_model_self_report",
        r"(?:Langfuse|Qdrant|RabbitMQ|aio-?pika|DeepSeek|深度求索|大语言模型|大模型|语言模型)",
    ),
)
REPLACEMENT = "[已脱敏]"


class SanitizeResult(BaseModel):
    """脱敏结果（hits 进 RunTrace.leak_hits——泄漏事件可追溯）。"""

    content: str
    hits: tuple[str, ...] = ()


def sanitize(content: str, extra_patterns: Sequence[tuple[str, str]] = ()) -> SanitizeResult:
    """命中一律替换不重生成不阻断（第三道审核的口径——安全但不断流）。"""
    hits: list[str] = []
    sanitized = content
    for name, pattern in (*LEAK_PATTERN_SPECS, *extra_patterns):
        sanitized, count = re.subn(pattern, REPLACEMENT, sanitized)
        if count:
            hits.extend([name] * count)
    return SanitizeResult(content=sanitized, hits=tuple(hits))
