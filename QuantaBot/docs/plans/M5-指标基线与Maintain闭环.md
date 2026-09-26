# M5-指标基线与Maintain闭环 实施计划（M5 计划 3/4）

> **For agentic workers:** 本计划按 AGENTS.md §6 工作流执行（步内 TDD 红绿节拍、每任务一提交、完成后独立审查）。git 命令在 `QuantaCommunity` 根仓库执行，路径带 `QuantaBot/` 前缀。
> **前置依赖**：`M5-熔断与成本分档.md`、`M5-灰度频率与指标打点.md` 已合入（依赖其 decisions 表 `duration_ms`/`cost_li` 列与成本键口径）。

**Goal:** 两个运维脚本就绪——`report_metrics.py`（提交成功率/管线耗时 P95/成本日报，Agent 侧观测口径）与 `reconcile_cost.py`（Redis 日键 vs Langfuse 逐次记录每日对账，>5% 差异告警）；Maintain 闭环落成文档流程（AGENTS.md §6.5 复盘模板 + 已知坑留档）。PRD 的真实审核通过率与触发至最终可见延迟待 demo0 联调后验证。

**Architecture:** 指标聚合以 SQLite decisions 表为唯一数据源（M4 起每条触发必落行、M5 起带时长与成本列）——不依赖 Langfuse 可用性。当前报告的 `replied` 只表示 Agent 生成完成并提交主服务写库，`success_rate` 的报告标签为“提交成功率”；`duration_ms` 是 Agent 管线从开始处理到终态/提交写库的耗时，排除 MQ 排队与 demo0 异步机审、最终可见延迟。真实 PRD 审核通过率与触发至最终可见延迟需要主服务回传终审结果，标记为“待联调后验证”。对账以 Redis 日键为实时侧、Langfuse 为事后侧，双向差异都报（Langfuse 上报失败 WARNING 不抛会丢点，容忍 5%）。当前 `cost_li`/Redis 日键只覆盖已接线的生成调用；决策、摘要、RAG/审核等其他 LLM 调用成本属于已知缺口，本计划不宣称全量账单。举报率数据在 demo0 侧，**只定义口径不动 demo0 代码**（红线：禁止改动）。

**Tech Stack:** stdlib + aiosqlite + langfuse SDK（均既有依赖，零新包）。

**Spec:** `总计划.md` §2 M5 节（M5-指标基线、M5-Maintain闭环两条 checkbox 的交付侧）+ PRD §4 指标表 + 本文件「需求结论」。grill 共识落地第 13/14/17 条。

## Global Constraints

- 同计划 1 的 Global Constraints 全文适用。
- M5 Agent 报告口径：提交成功率 = `replied` / 全部 Agent 决策行；管线 P95 = `replied` 样本的 `duration_ms`（从管线开始处理到主服务写库提交/终态）。这两个指标不包含 demo0 异步终审；真实 PRD 审核通过率与触发至最终可见延迟标记“待联调后验证”。
- 当前 `cost_li` 只覆盖生成调用；决策、摘要、RAG/审核等其他 LLM 成本暂不纳入本报告，成本对账只对同一已接线口径核对，不把结果写成全量账单。
- 决策日志统计口径诚实（AGENTS §5 Do-4）：超时/拦截不计成功——脚本按 decision 枚举精确分桶，不做模糊匹配。
- 举报率不写代码采集（demo0 侧数据，改 demo0 需显式授权）——本计划只落口径文档与人工导出步骤。

## 需求结论（grill-me 共识摘录）

1. 打点已在计划 2 落地（duration_ms/stage_ms/cost_li 进 SQLite+Langfuse）；本计划做提交成功率、管线耗时 P95、成本聚合与对账。真实审核通过率/最终可见延迟待联调后验证。
2. 对账：`reconcile_cost.py` 手动触发（M5 演练跑一次作证据），部署后可挂 cron；差异容忍 >5% 告警（来源：Langfuse 丢点 + fake 调用不计费）。
3. 指标基线"就绪即验收"：口径+脚本+演练证据；持续 30 天采集随实际部署启动（部署时点管理员定，不塞进 M5 验收）。
4. 轻模型暂不跑 Persona eval（已知风险留档，Maintain 观察项）；M4 格式护栏不预做（触发条件= M5 后仍出现随机违规）。
5. Maintain 闭环 = 复盘模板（问题→根因→修复→固化动作）+ 坑固化为回归用例（不依赖记性防二犯）。

---

### Task 1: 指标日报脚本（scripts/report_metrics.py）

**Files:**
- Create: `QuantaBot/scripts/report_metrics.py`
- Test: `QuantaBot/tests/unit/scripts/test_report_metrics.py`

**Interfaces:**
- Consumes: SQLite `decisions` 表（计划 2 扩列后的 7 数据列）；`budget.cost_key` 命名口径（仅注释引用）。
- Produces:
  - `compute_metrics(rows: Sequence[Mapping[str, object]]) -> dict`（纯函数：rows=decision/duration_ms/cost_li 字段集 → 指标 dict）
  - `async def fetch_rows(db_path: Path, day: date) -> list[dict]`
  - `def write_report(metrics: dict, output_dir: Path, day: date) -> tuple[Path, Path]`（JSON+Markdown 双写）
  - CLI：`uv run python scripts/report_metrics.py --db data/decisions.db --date 2026-09-25 --output-dir eval/reports`

- [ ] **Step 1: 写失败测试**

新建 `tests/unit/scripts/test_report_metrics.py`：

```python
"""report_metrics 单测：口径正确性（诚实分桶/P95 边界/成本求和）与产物落盘。"""

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))  # scripts 非包——路径注入导入（沿用 scripts 测试既有口径）

from report_metrics import compute_metrics, write_report  # noqa: E402


def _row(decision: str, duration_ms: int | None, cost_li: int | None = None) -> dict:
    return {"decision": decision, "duration_ms": duration_ms, "cost_li": cost_li}


def test_compute_metrics_honest_bucketing() -> None:
    rows = [
        _row("replied", 100, 10),
        _row("replied", 200, 20),
        _row("failed", 9000),  # 超时/失败不计成功（AGENTS Do-4 诚实口径）
        _row("skipped_low_value", 5),
        _row("rejected_moderation", 8),  # 审核拦截不计提交成功，但归入 skipped_total
    ]
    metrics = compute_metrics(rows)
    assert metrics["total"] == 5
    assert metrics["replied"] == 2
    assert metrics["success_rate"] == pytest.approx(0.4)  # 2/5
    assert metrics["failed"] == 1
    assert metrics["skipped_total"] == 2  # low_value + rejected_moderation
    assert metrics["total_cost_li"] == 30  # 成本只对有 cost_li 的行求和


def test_compute_metrics_p95_boundary() -> None:
    rows = [_row("replied", index * 100) for index in range(1, 21)]  # 100..2000 共 20 条
    metrics = compute_metrics(rows)
    # P95：n=20 → ceil(0.95*20)=19 → 第 19 小 = 1900（非插值，保守取序统计量）
    assert metrics["p95_duration_ms"] == 1900
    assert metrics["avg_duration_ms"] == pytest.approx(1050)


def test_compute_metrics_empty_and_missing_durations() -> None:
    metrics = compute_metrics([])
    assert metrics["total"] == 0
    assert metrics["success_rate"] == 0.0
    assert metrics["p95_duration_ms"] is None  # 无样本=无 P95（不造 0）
    assert compute_metrics([_row("replied", None)])["p95_duration_ms"] is None  # 缺时长样本跳过


def test_write_report_outputs_json_and_markdown(tmp_path) -> None:
    metrics = compute_metrics([_row("replied", 120, 8)])
    json_path, markdown_path = write_report(metrics, tmp_path, date(2026, 9, 25))
    assert json_path.name == "metrics-2026-09-25.json"
    assert markdown_path.name == "metrics-2026-09-25.md"
    assert "success_rate" in json_path.read_text(encoding="utf-8")
    assert "提交成功率" in markdown_path.read_text(encoding="utf-8")
```

- [ ] **Step 2: 跑红**

Run: `cd QuantaCommunity; uv run --project QuantaBot pytest QuantaBot/tests/unit/scripts/test_report_metrics.py -q`
Expected: FAIL（`ModuleNotFoundError: report_metrics`）

- [ ] **Step 3: 写实现**

新建 `scripts/report_metrics.py`：

```python
"""report_metrics —— 指标基线日报（M5：提交成功率/管线耗时 P95/成本）。

职责：从 SQLite decisions 表聚合当日指标并落 JSON+Markdown 双产物（eval/reports/）。
口径（M5 Agent 观测，诚实分桶）：提交成功率 = replied / total（Agent 生成完成并提交主服务
      写库才计入；demo0 异步机审与最终可见性不在本脚本）；管线耗时 P95 = replied 样本
      duration_ms 的 95 分位（序统计量 ceil(0.95n)，非插值——保守取实际样本）；成本 =
      当前已接线生成调用行的 cost_li 求和（厘），不宣称覆盖决策、摘要、RAG/审核等其他 LLM。
      真实 PRD 审核通过率与触发至最终可见延迟待联调后验证。
边界：只读 SQLite（不碰 Langfuse/Redis——观测近端兜底数据源）；不设阈值判定（基线采集期
      只出数不设门，硬门槛属上线后校准）；举报率不在本脚本（数据在 demo0 侧，人工导出）。
用法：uv run python scripts/report_metrics.py --db data/decisions.db --date 2026-09-25 --output-dir eval/reports
"""

import argparse
import asyncio
import json
import math
from collections.abc import Mapping, Sequence
from datetime import UTC, date as date_type, datetime
from pathlib import Path

import aiosqlite

# 决策枚举分桶口径（与 crosscutting/ports.py 的 Decision 一致——此处不 import 生产包，脚本独立可跑）
_REPLIED = "replied"
_SKIPPED_PREFIX = "skipped_"


def compute_metrics(rows: Sequence[Mapping[str, object]]) -> dict:
    """按 M5 Agent 观测口径聚合（纯函数：决策分桶/提交成功率/管线 P95/生成成本求和）。"""
    total = len(rows)
    decision_counts: dict[str, int] = {}
    replied_durations: list[int] = []
    total_cost_li = 0
    for row in rows:
        decision = str(row["decision"])
        decision_counts[decision] = decision_counts.get(decision, 0) + 1
        if row.get("cost_li") is not None:  # 成本对有值行求和（skipped/failed 行通常无值）
            total_cost_li += int(row["cost_li"])  # type: ignore[arg-type]
        if decision == _REPLIED and row.get("duration_ms") is not None:
            replied_durations.append(int(row["duration_ms"]))  # type: ignore[arg-type]
    replied = decision_counts.get(_REPLIED, 0)
    p95 = _percentile_95(replied_durations)
    avg = round(sum(replied_durations) / len(replied_durations)) if replied_durations else None
    return {
        "total": total,
        "replied": replied,
        "failed": decision_counts.get("failed", 0) + decision_counts.get("failed_breaker", 0),
        "skipped_total": sum(
            count
            for key, count in decision_counts.items()
            if key.startswith(_SKIPPED_PREFIX) or key == "rejected_moderation"
        ),
        "decision_counts": decision_counts,
        "success_rate": round(replied / total, 4) if total else 0.0,
        "p95_duration_ms": p95,
        "avg_duration_ms": avg,
        "total_cost_li": total_cost_li,
    }


def _percentile_95(sorted_values: list[int]) -> int | None:
    """P95 序统计量：ceil(0.95n) 位置的样本值（非插值——保守取向）。"""
    if not sorted_values:
        return None
    ordered = sorted(sorted_values)
    index = min(math.ceil(0.95 * len(ordered)), len(ordered)) - 1
    return ordered[index]


async def fetch_rows(db_path: Path, day: date_type) -> list[dict]:
    """读当日 decisions 行（created_at 存 ISO8601 UTC 文本，按日期前缀过滤）。"""
    async with aiosqlite.connect(db_path) as db:
        cursor = await db.execute(
            "SELECT decision, duration_ms, cost_li FROM decisions WHERE created_at LIKE ? ORDER BY id",
            (f"{day.isoformat()}%",),
        )
        rows = await cursor.fetchall()
    return [
        {"decision": row[0], "duration_ms": row[1], "cost_li": row[2]} for row in rows
    ]


def write_report(metrics: dict, output_dir: Path, day: date_type) -> tuple[Path, Path]:
    """落 JSON（机器读）+ Markdown（人读）双产物。"""
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"metrics-{day.isoformat()}.json"
    markdown_path = output_dir / f"metrics-{day.isoformat()}.md"
    json_path.write_text(
        json.dumps({"date": day.isoformat(), **metrics}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    cost_yuan = metrics["total_cost_li"] / 1000  # 厘 → 元
    lines = [
        f"# 指标基线日报 {day.isoformat()}",
        "",
        f"- 触发总量：{metrics['total']}",
        f"- 提交成功率：{metrics['success_rate']:.1%}（replied {metrics['replied']} / {metrics['total']}；不含 demo0 异步终审）",
        f"- 管线耗时 P95：{metrics['p95_duration_ms'] if metrics['p95_duration_ms'] is not None else 'N/A'} ms（不含 MQ 排队与最终可见延迟）",
        f"- 管线平均耗时：{metrics['avg_duration_ms'] if metrics['avg_duration_ms'] is not None else 'N/A'} ms",
        f"- 当日成本：{metrics['total_cost_li']} 厘（{cost_yuan:.2f} 元，PRD 目标 ≤30 元）",
        f"- 静默分桶：skipped {metrics['skipped_total']} / failed {metrics['failed']}",
        f"- 决策明细：{metrics['decision_counts']}",
        "",
        "> PRD 真实审核通过率与触发至最终可见延迟：待 demo0 联调后验证。",
        "> 举报率口径：被举报 AI 回复 / AI 总回复数——数据在 demo0 侧，人工导出（M5 不动 demo0）。",
    ]
    markdown_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, markdown_path


def main() -> int:
    """CLI 入口（默认库=项目根 data/decisions.db；默认日期=UTC 今日）。"""
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="QuantaBot 指标基线日报（M5 Agent 提交成功率/管线耗时/生成成本口径）")
    parser.add_argument("--db", default=str(project_root / "data" / "decisions.db"))
    parser.add_argument("--date", default=datetime.now(UTC).date().isoformat(), help="YYYY-MM-DD（UTC）")
    parser.add_argument("--output-dir", default=str(project_root / "eval" / "reports"))
    args = parser.parse_args()
    day = date_type.fromisoformat(args.date)
    rows = asyncio.run(fetch_rows(Path(args.db), day))
    metrics = compute_metrics(rows)
    json_path, markdown_path = write_report(metrics, Path(args.output_dir), day)
    print(f"[report_metrics] {day.isoformat()}: success_rate={metrics['success_rate']:.1%} "
          f"p95={metrics['p95_duration_ms']}ms cost={metrics['total_cost_li']}厘")
    print(f"[report_metrics] 产物：{json_path} / {markdown_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 跑绿 + 真库冒烟**

```bash
cd QuantaCommunity
uv run --project QuantaBot pytest QuantaBot/tests/unit/scripts/test_report_metrics.py -q
uv run --project QuantaBot python QuantaBot/scripts/report_metrics.py --date 2026-09-25
```
Expected: 测试全绿；脚本对当日（可能为空）库正常出产物（total=0 也输出报告）

- [ ] **Step 5: Commit**

```bash
cd QuantaCommunity
git add QuantaBot/scripts/report_metrics.py QuantaBot/tests/unit/scripts/test_report_metrics.py
git commit -m "feat(m5): 指标基线日报脚本（提交成功率/管线 P95/生成成本）——影响面：观测侧新增，零生产代码改动"
```

---

### Task 2: 成本对账脚本 + Maintain 闭环文档（AGENTS §6.5 + 风险留档）

**Files:**
- Create: `QuantaBot/scripts/reconcile_cost.py`
- Test: `QuantaBot/tests/unit/scripts/test_reconcile_cost.py`
- Modify: `QuantaBot/AGENTS.md`（新增 §6.5 故障复盘与经验固化流程）
- Modify: `QuantaBot/docs/技术选型.md`（§4.7 追加「M5 实施注记」小节）

**Interfaces:**
- Consumes: `budget.cost_key(day)`（Redis 键名）；本机 `.venv` Langfuse 的 `client.api.observations.get_many`（V4 真实签名与 cursor 分页）；`Settings`（redis/langfuse 配置）。
- Produces:
  - `def compare_costs(redis_cost_li: int, langfuse_cost_li: int, tolerance_pct: float) -> dict`（纯函数：diff/占比/是否告警）
  - `def fetch_langfuse_cost_li(client: object, day: date) -> int`（遍历当日 traces 的 `pipeline.run` observation metadata 求和）
  - CLI：`uv run python scripts/reconcile_cost.py --date 2026-09-25 [--tolerance-pct 5]`，退出码 0=对账通过 / 1=差异超容忍

- [ ] **Step 1: 写失败测试**

新建 `tests/unit/scripts/test_reconcile_cost.py`：

```python
"""reconcile_cost 单测：差异判定边界 + Langfuse 侧求和（stub client 注入）。"""

import sys
from datetime import date
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from reconcile_cost import compare_costs, fetch_langfuse_cost_li  # noqa: E402


def test_compare_costs_within_tolerance() -> None:
    verdict = compare_costs(redis_cost_li=10000, langfuse_cost_li=9700, tolerance_pct=5.0)
    assert verdict["within_tolerance"] is True  # 3% 差异 < 5%
    assert verdict["diff_li"] == 300


def test_compare_costs_exceeds_tolerance() -> None:
    verdict = compare_costs(redis_cost_li=10000, langfuse_cost_li=8000, tolerance_pct=5.0)
    assert verdict["within_tolerance"] is False  # 20% 差异告警


def test_compare_costs_zero_sides_do_not_divide_by_zero() -> None:
    verdict = compare_costs(redis_cost_li=0, langfuse_cost_li=0, tolerance_pct=5.0)
    assert verdict["within_tolerance"] is True  # 双零=对账一致
    assert compare_costs(0, 0, 5.0)["diff_pct"] == 0.0


class _Observation:
    def __init__(self, name: str, metadata: dict, is_root_observation: bool = True) -> None:
        self.name = name
        self.metadata = metadata
        self.is_root_observation = is_root_observation


class _Meta:
    def __init__(self, cursor: str | None) -> None:
        self.cursor = cursor


class _Page:
    def __init__(self, data: list[_Observation], cursor: str | None) -> None:
        self.data = data
        self.meta = _Meta(cursor)


class _StubObservations:
    """Langfuse V4 observations.get_many stub（两页：3 条 + 1 条）。"""

    def __init__(self) -> None:
        self.pages = [
            _Page(
                [
                    _Observation("pipeline.run", {"cost_li": 100}),
                    _Observation("generation", {"cost_li": 999}, is_root_observation=False),
                    _Observation("pipeline.run", {"cost_li": 200}),
                ],
                cursor="page-2",
            ),
            _Page([_Observation("pipeline.run", {"cost_li": 50})], cursor=None),
        ]
        self.calls = 0
        self.call_args: list[dict] = []

    def get_many(self, **kwargs):
        self.call_args.append(kwargs)
        page = self.pages[self.calls]
        self.calls += 1
        return page


class _StubLangfuse:
    def __init__(self) -> None:
        self.api = SimpleNamespace(observations=_StubObservations())


def test_fetch_langfuse_cost_sums_root_observations() -> None:
    client = _StubLangfuse()
    total = fetch_langfuse_cost_li(client, date(2026, 9, 25))
    assert total == 350  # 100+200+50（generation 干扰项 999 不计；两页 cursor 分页）
    assert client.api.observations.calls == 2
    assert client.api.observations.call_args[0]["cursor"] is None
    assert client.api.observations.call_args[1]["cursor"] == "page-2"
```

- [ ] **Step 2: 跑红**

Run: `cd QuantaCommunity; uv run --project QuantaBot pytest QuantaBot/tests/unit/scripts/test_reconcile_cost.py -q`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 写实现**

**(a) 新建 `scripts/reconcile_cost.py`：**

```python
"""reconcile_cost —— 成本每日对账（M5：Redis 日累计 vs Langfuse 逐次记录，G6）。

职责：通过 Langfuse V4 `client.api.observations.get_many` 拉当日 `pipeline.run` observation 的 cost_li 总和，与 Redis 日键
      累计对比；差异 > 容忍度（默认 5%）退出码 1 + 告警项（部署后可挂 cron，M5 内手动触发）。
口径：Redis=实时控制依据，Langfuse=事后核对依据（技术选型 §4.7）；双向差异都报——
      Langfuse 上报失败 WARNING 不抛会丢点（redis 偏大），fake/异常调用不计费（偏差两向皆有）。
边界：只读两侧数据不改任何键；成本字段是当前已接线的生成 `cost_li`，不宣称覆盖其他 LLM 调用；
      Redis 不可读=基础设施失败退出码 2（对账是对账，不能在依赖故障时假装通过）。
用法：uv run python scripts/reconcile_cost.py --date 2026-09-25 [--tolerance-pct 5]
"""

import argparse
import asyncio
import json
import sys
from datetime import UTC, date as date_type, datetime, timedelta
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from quanta_bot.crosscutting import budget  # noqa: E402
from quanta_bot.infra.kv import RedisKV  # noqa: E402
from quanta_bot.infra.settings import Settings  # noqa: E402

# 本仓库 .venv 当前为 langfuse 4.15.2：ObservationsClient.get_many 的真实分页签名使用
# fields/expand_metadata/limit/cursor/name/is_root_observation/from_start_time/to_start_time，
# 返回 ObservationsV2Response(data, meta.cursor)。不要改回不存在的 fetch_traces API。
_SDK_NOTE = "langfuse 4.15.2 observations.get_many；cost_li 为当前生成调用观测口径"


def compare_costs(redis_cost_li: int, langfuse_cost_li: int, tolerance_pct: float) -> dict:
    """差异判定（纯函数；分母取 max(两侧,1) 防零除）。"""
    diff_li = abs(redis_cost_li - langfuse_cost_li)
    denominator = max(redis_cost_li, langfuse_cost_li, 1)
    diff_pct = round(diff_li / denominator * 100, 2)
    return {
        "redis_cost_li": redis_cost_li,
        "langfuse_cost_li": langfuse_cost_li,
        "diff_li": diff_li,
        "diff_pct": diff_pct,
        "tolerance_pct": tolerance_pct,
        "within_tolerance": diff_pct <= tolerance_pct,
    }


def fetch_langfuse_cost_li(client: object, day: date_type) -> int:
    """按 V4 observations.get_many 的 cursor 分页求当日根 pipeline.run cost_li。"""
    from_start_time = datetime(day.year, day.month, day.day, tzinfo=UTC)
    to_start_time = from_start_time + timedelta(days=1)
    total = 0
    cursor: str | None = None
    while True:
        response = client.api.observations.get_many(
            fields="core,basic,metadata",
            expand_metadata="cost_li",
            limit=50,
            cursor=cursor,
            name="pipeline.run",
            is_root_observation=True,
            from_start_time=from_start_time,
            to_start_time=to_start_time,
        )
        for observation in response.data:
            if observation.name != "pipeline.run":
                continue
            cost = (observation.metadata or {}).get("cost_li")
            if cost is not None:
                total += int(cost)
        cursor = response.meta.cursor
        if not cursor:
            break
    return total


async def _read_redis_cost(settings: Settings, day: date_type) -> int:
    kv = RedisKV(settings.redis_url, settings.redis_timeout_seconds)
    try:
        raw = await kv.get(budget.cost_key(day))
        return int(raw) if raw is not None else 0
    finally:
        await kv.aclose()


def main() -> int:
    """CLI 入口：0=对账通过；1=差异超容忍；2=基础设施失败（Redis/Langfuse 不可达）。"""
    parser = argparse.ArgumentParser(description="QuantaBot 成本每日对账（Redis vs Langfuse）")
    parser.add_argument("--date", default=datetime.now(UTC).date().isoformat())
    parser.add_argument("--tolerance-pct", type=float, default=5.0)
    args = parser.parse_args()
    day = date_type.fromisoformat(args.date)
    settings = Settings()
    try:
        redis_cost = asyncio.run(_read_redis_cost(settings, day))
    except Exception as exc:
        print(f"[reconcile_cost] Redis 读取失败（基础设施失败，不假装通过）：{exc}", file=sys.stderr)
        return 2
    try:
        from langfuse import Langfuse

        client = Langfuse(
            public_key=settings.langfuse_public_key,
            secret_key=settings.langfuse_secret_key,
            host=settings.langfuse_host,
        )
        langfuse_cost = fetch_langfuse_cost_li(client, day)
    except Exception as exc:
        print(f"[reconcile_cost] Langfuse 拉取失败（基础设施失败）：{exc}", file=sys.stderr)
        return 2
    verdict = compare_costs(redis_cost, langfuse_cost, args.tolerance_pct)
    report_path = PROJECT_ROOT / "eval" / "reports" / f"reconcile-{day.isoformat()}.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps({"date": day.isoformat(), **verdict, "note": _SDK_NOTE}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    status = "PASS" if verdict["within_tolerance"] else "ALERT"
    print(f"[reconcile_cost] {status} redis={verdict['redis_cost_li']}厘 langfuse={verdict['langfuse_cost_li']}厘 "
          f"diff={verdict['diff_pct']}%（容忍 {args.tolerance_pct}%）→ {report_path}")
    return 0 if verdict["within_tolerance"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
```

**(b) `AGENTS.md` 新增 §6.5**（§6.4 Git 约定之后、结尾前插入；文件头部变更记录追加 `v0.4.7（2026-09-25）——M5 落地：新增 §6.5 故障复盘与经验固化流程（Maintain 闭环）`）：

```markdown
### 6.5 故障复盘与经验固化（Maintain 闭环，M5 起）

线上问题（真实故障/演练暴露缺陷/评测随机违规）修复后，48h 内完成三步，不依赖记性防二犯：

1. **复盘回写**：在 `总计划.md` §7 变更记录追加一行（现象→根因→修复 commit）；
   影响面大的另开 `docs/plans/<里程碑>-复盘.md`（参照 M4-评测门禁与红队-实施复盘.md 结构）。
2. **坑固化**：踩过的坑转化为可执行回归用例进 `tests/unit` 或 `tests/eval`（断言行为，
   不 mock 掉被测物）；无法写成用例的（如外部工具缺陷）在 `docs/技术选型.md` 风险注记留档。
3. **触发条件观察项**：修复时埋下的"若再出现则升级"条款（如 M4 复盘 §8 的写库前格式护栏），
   写进本节维护的观察清单，触发即立项。

**观察清单（M5 建立时点）**：
- flash 档输出对冻结 P0 风格断言的逐轮随机违规——若 M5 后仍出现，立项"写库前确定性格式护栏"
  （与泄漏扫描同构），不再提示词打地鼠（M4 复盘 §8）。
- 轻模型（Qwen3.5-Plus）生成质量未跑 Persona eval（成本决策）——若吃紧档回复出现 P0 级硬伤，
  kill switch 止血后补跑轻模型 Persona 单轮（P0 零失败门槛）。
- Promptfoo resume 路径稳定性（M4 已知运行风险）——红队复跑前检查。

**复盘模板**（开复盘文档时复制）：
> ## <问题标题>（<日期>）
> - 现象：<可观察的事实，含证据链接>
> - 根因：<技术根因，不含责任人>
> - 修复：<commit hash + 一句话>
> - 固化：<新增的回归用例/断言/文档留档位置>
> - 观察项：<若有"再出现则升级"条款，写明触发条件与升级动作>
```

**(c) `docs/技术选型.md` §4.7（成本配额节）末尾追加「M5 实施注记」小节**（找到 §4.7 的对账口径段落后插入）：

```markdown
**M5 实施注记（2026-09-25）**：分档阈值落地为吃紧 20000 厘 / 枯竭 28000 厘（Settings 可配，
30 元目标留 2 元在途收敛缓冲）；吃紧档仅切生成（决策/摘要保持主模型 deepseek-v4-flash 保判断
质量），轻模型 = Qwen3.5-Plus（OpenAI 兼容端点复用 DeepSeekClient 形态）；对账脚本 =
`scripts/reconcile_cost.py`（容忍 5%，退出码 0/1/2）；轻模型生成质量**未跑 Persona eval**
（成本决策留档，触发升级条件见 AGENTS.md §6.5 观察清单）。举报率口径：被举报 AI 回复 /
AI 总回复数——数据在 demo0 侧举报表，M5 不动 demo0 代码，人工导出核算；指标基线持续采集
（30 天窗口）随实际部署启动，`scripts/report_metrics.py` 为口径就绪交付物。
```

- [ ] **Step 4: 跑绿 + 全量**

```bash
cd QuantaCommunity
uv run --project QuantaBot pytest QuantaBot/tests/unit -q
uv run --project QuantaBot ruff format QuantaBot/scripts QuantaBot/tests
uv run --project QuantaBot ruff check QuantaBot/scripts QuantaBot/tests
```
Expected: 全绿 / 零违规（ruff 若未覆盖 scripts 目录则在 pyproject 的 ruff 目标里确认 scripts 已纳入——CI 的 `ruff check src tests` 口径不含 scripts，本步显式跑）

- [ ] **Step 5: Commit**

```bash
cd QuantaCommunity
git add QuantaBot/scripts/reconcile_cost.py QuantaBot/tests QuantaBot/AGENTS.md QuantaBot/docs/技术选型.md
git commit -m "feat(m5): 成本对账脚本 + Maintain 闭环流程（AGENTS §6.5 复盘模板/观察清单 + 技术选型实施注记）——影响面：观测与流程，零生产链路改动"
```

---

## 完成定义（本计划文件）

1. 两脚本单测全绿 + `report_metrics.py` 对真实库冒烟出产物；
2. AGENTS.md §6.5（复盘流程+观察清单+模板）与技术选型 M5 实施注记落盘；
3. 独立审查通过（重点查脚本口径与 PRD §4 的一致性），交付摘要回主会话。

## 明确不做（本文件边界）

- 对账脚本不在 CI 常跑（依赖真栈；演练归计划 4）；cron 挂载随部署（口径就绪即验收）。
- 不动 demo0（举报数据回流、Grafana 告警联动均属 demo0 侧后续优化总方案范围）。
- 不设指标硬门槛（M4 方案明确"只采集，M5 定硬门槛"中"定门槛"动作随上线后 30 天基线数据做——本计划只交付口径与数据管道，门槛数字不在 M5 拍板）。
