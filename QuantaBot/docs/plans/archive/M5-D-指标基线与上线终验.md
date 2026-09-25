# M5-D 指标基线与上线终验 实施计划

> **For agentic workers:** 按 Task 顺序执行；Task 1「先完整实现 → 统一批量测试」，Task 2/3 为真栈演练与终验操作（需要管理员配合启动 demo0）。步骤用 checkbox 跟踪。**前置：M5-A/B/C 已全部合入。**

**Goal:** 指标观测口径就绪（duration 打点 + 成功率/P95/成本日报 + Langfuse 每日对账）、三场横切演练（kill 止血/灰度回滚/成本分档）真栈闭环、PRD 上线前置四条件终验 + 最终 SHA Release gate 复跑、Maintain 闭环落档，M5 整体收官。

**Architecture:** pipeline 全程 `duration_ms` 进 RunTrace+SQLite（P95 数据源就近落盘）；`scripts/report_metrics.py` 从 SQLite+Redis 聚合日报；`scripts/reconcile_cost.py` 对账 Langfuse generation 成本 vs Redis 日键（>5% 告警）；演练与终验按 M3 轨道 A 口径在本地全真栈执行，证据落 `eval/reports/`。

**Tech Stack:** aiosqlite / langfuse SDK（已装）/ redis（经 RedisKV）。无新依赖。

**Spec:** `QuantaBot/总计划.md` §2 M5 节 + §4 汇总表 + 本文件「需求结论」。执行前先读 `QuantaBot/AGENTS.md` §0/§3（门禁命令）/§6.3。

---

## 需求结论（grill-me 两轮对齐，2026-09-25）

1. **指标交付到「口径+脚本+演练就绪」**：成功率/P95/成本 Agent 侧全自动；**举报率只定口径**（数据在 demo0 侧，红线禁改 demo0——人工导出）；「持续采集 30 天」随实际部署启动（部署时点管理员定），不塞进 M5 验收。
2. **成功率先手口径**：`replied / (全部决策行 - skipped_not_mentioned - skipped_idempotent)`——超时/拦截/熔断/成本枯竭/频率灰度拦截都不算成功但留在分母（PRD F6 诚实口径：模型超时/审核拦截不计入成功回复）。
3. **P95**：replied 行 duration_ms 升序 95 分位（nearest-rank）；口径=触发处理开始到终态（PRD ≤30s 目标）。
4. **对账**：`reconcile_cost.py` 手动触发（M5 内跑一次作为演练证据），差异 >5% 输出告警项（来源：Langfuse 上报失败 WARNING 丢点、fail-open 缺口）；部署后可挂 cron，不强制常驻。
5. **三场演练**（本地全真栈，同 M3 轨道 A 口径：demo0 + QuantaBot compose 全家桶，评论机审开启）：kill 置位止血（≤5s 暂停、期间零新回复、决策日志留痕）/ 灰度放量+persona_version 回滚（旧版本记忆检索返回空）/ 成本键打满分档（枯竭静默 + 吃紧切轻模型）。证据文件落 `eval/reports/m5-drill-*.md`。
6. **终验**：四条件=幂等（重投同 comment_id 真栈证据，演练附带）/ 审核三层（单测+M3 机审 fixture 证据引用）/ 决策日志（SQLite+Langfuse trace 抽查留档）/ 评测回归（**最终冻结 SHA 复跑 Release gate**：Persona 双轮 + 红队 75，真实费用约 1.5h）。
7. **Maintain 闭环**：复盘模板落档 + 已知坑固化清单（waterline 同秒穿越→已有 pipeline-waterline case 防回归；flash 随机违规→触发条件=写库前确定性格式护栏；Promptfoo resume 路径→触发条件；轻模型盲飞→触发条件=吃紧档常态化后补跑轻模型 P0 门）；M4 复盘原文口径「格式护栏 M5 后仍出现才考虑」——**不预做**。

## Global Constraints

（同 M5-A/B/C，全文适用）
- **任务粒度（用户 2026-09-25 定制）**：大块 Task，先完整实现再统一批量测试；测试只打核心链路（口径计算、分位算法、对账比较），不写琐碎小测。
- 分层硬规则；SQL 只写在 `infra/audit_db.py`（脚本经它查询，不裸写）；配置收口 settings.py；中文注释。
- **禁止改动 demo0 / demo0-admin / demo0-miniprogram 代码**（演练只操作：demo0 界面发评、redis-cli 写键、docker compose 起 QuantaBot 栈）。
- git 在 `QuantaCommunity` 根执行；Conventional Commits；演练证据文件（m5-drill-*.md）可入 Git（脱敏口径同 eval/reports）。
- 每 Task 完成必跑 ruff 双命令 + `uv run pytest tests/unit -q`（Task 2/3 为操作类，以证据文件代替）。

## 文件结构

- Create: `scripts/report_metrics.py`、`scripts/reconcile_cost.py`
- Create: `tests/unit/scripts/test_report_metrics.py`、`tests/unit/scripts/test_reconcile_cost.py`
- Create: `docs/plans/故障复盘模板.md`（Maintain 闭环载体）
- Create（演练产物）: `eval/reports/m5-drill-killswitch.md`、`eval/reports/m5-drill-graylist-rollback.md`、`eval/reports/m5-drill-cost-tiers.md`
- Modify: `src/quanta_bot/pipeline/ports.py`（RunTrace/DecisionLogEntry 增 duration_ms）
- Modify: `src/quanta_bot/pipeline/pipeline.py`（run() 全程计时）
- Modify: `src/quanta_bot/crosscutting/ports.py`（DecisionLogEntry 增 duration_ms）
- Modify: `src/quanta_bot/infra/audit_db.py`（表加列+旧库迁移+fetch_by_date）
- Modify: `QuantaBot/总计划.md`、`QuantaBot/AGENTS.md`、`QuantaBot/docs/技术选型.md`（终回写）

---

### Task 1: duration 打点 + 指标/对账脚本（实现 + 批量核心测试）

**Interfaces（Produces）:**
- `RunTrace.duration_ms: int | None = None`；`DecisionLogEntry.duration_ms: int | None = None`
- `SQLiteAudit.fetch_by_date(day: date) -> list[_Row]`（_Row 增 `duration_ms`）
- `scripts/report_metrics.py`：`success_rate(entries) -> float`、`percentile(values, pct) -> int | None`、`build_report(...) -> MetricsReport`、`render_markdown(report) -> str`、`async main(argv) -> int`
- `scripts/reconcile_cost.py`：`compare_costs(redis_cost_li, langfuse_cost_li, tolerance_pct=5.0) -> ReconcileResult`、`fetch_langfuse_cost_li(client, day) -> int`、`async main(argv) -> int`（差异>5% 退出码 1）

- [ ] **Step 1.1: duration 打点**

`pipeline/ports.py` RunTrace 末尾追加：

```python
    duration_ms: int | None = None  # 全链路耗时（触发处理开始到终态——P95 口径，M5-D）
```

`crosscutting/ports.py` DecisionLogEntry 追加（`reason` 之后）：

```python
    duration_ms: int | None = Field(default=None, description="全链路耗时毫秒（P95 观测，M5-D）")
```

`pipeline/pipeline.py` `run()` 改为：

```python
import time  # import 区补

async def run(event: TriggerEvent, deps: PipelineDeps) -> Decision:
    """跑一条触发事件的完整被动链路，返回终态决策值（单出口统一审计+上报）。"""
    started_monotonic = time.monotonic()  # P95 口径起点：触发处理开始
    outcome = await _execute(event, deps)  # 执行链路各分支
    duration_ms = round((time.monotonic() - started_monotonic) * 1000)  # 全链路耗时
    await deps.audit.record(  # 记录决策日志
        DecisionLogEntry(
            comment_id=event.comment_id,
            decision=outcome.decision,
            mode=outcome.mode,
            reason=outcome.reason,
            duration_ms=duration_ms,
        )
    )
    ...（RunTrace 构造追加 duration_ms=duration_ms，其余不变）
```

- [ ] **Step 1.2: audit_db 加列 + 迁移 + 按日查询**

`infra/audit_db.py`：`_SCHEMA` 表定义追加 `duration_ms INTEGER` 列；新增迁移语句与按日查询：

```python
# 旧库迁移：M5-D 前的 decisions 表无 duration_ms（新库建表已带列，ALTER 抛 duplicate column——忽略）
_ADD_DURATION_COLUMN = "ALTER TABLE decisions ADD COLUMN duration_ms INTEGER"

# 查询行（fetch_entries/fetch_by_date 返回形态；行序=写入序）
_Row = NamedTuple(
    "_Row",
    comment_id=int,
    decision=str,
    mode=str | None,
    reason=str,
    created_at=str,
    duration_ms=int | None,
)
```

`SQLiteAudit` 改造（record/fetch_entries 两处建表语句统一走 `_ensure_schema`；INSERT 增 duration_ms；新增 fetch_by_date）：

```python
    async def _ensure_schema(self, db: aiosqlite.Connection) -> None:
        """建表（新库含 duration_ms）+ 旧库补列迁移。"""
        await db.execute(_SCHEMA)
        try:
            await db.execute(_ADD_DURATION_COLUMN)
        except aiosqlite.OperationalError:  # 列已存在（新库）——正常路径
            pass

    async def record(self, entry: DecisionLogEntry) -> None:
        """落一条决策明细（自动建父目录与表；旧库自动迁移 duration_ms 列）。"""
        Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self._db_path) as db:
            await self._ensure_schema(db)
            await db.execute(
                "INSERT INTO decisions (comment_id, decision, mode, reason, created_at, duration_ms) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (
                    entry.comment_id,
                    entry.decision,
                    entry.mode,
                    entry.reason,
                    entry.created_at.isoformat(),
                    entry.duration_ms,
                ),
            )
            await db.commit()

    async def fetch_entries(self) -> list[_Row]:
        """按写入序查回全部决策明细（测试/自查用，不在端口契约上）。"""
        async with aiosqlite.connect(self._db_path) as db:
            await self._ensure_schema(db)
            cursor = await db.execute(
                "SELECT comment_id, decision, mode, reason, created_at, duration_ms "
                "FROM decisions ORDER BY id"
            )
            rows = await cursor.fetchall()
        return [_Row(*row) for row in rows]

    async def fetch_by_date(self, day: date) -> list[_Row]:
        """按 UTC 日期查回当日决策明细（report_metrics 日报数据源——M5-D）。"""
        async with aiosqlite.connect(self._db_path) as db:
            await self._ensure_schema(db)
            cursor = await db.execute(
                "SELECT comment_id, decision, mode, reason, created_at, duration_ms "
                "FROM decisions WHERE created_at LIKE ? ORDER BY id",
                (f"{day.isoformat()}%",),
            )
            rows = await cursor.fetchall()
        return [_Row(*row) for row in rows]
```

（import 区补 `from datetime import date`；docstring 边界行更新。）

- [ ] **Step 1.3: scripts/report_metrics.py（完整文件）**

```python
"""scripts/report_metrics —— M5 指标基线日报（成功率/P95/成本；举报率口径提示）。

口径（PRD §4 + 诚实统计，AGENTS §5 Do「模型超时/审核拦截不算成功回复」）：
- 回复成功率 = replied / (全部决策行 - skipped_not_mentioned - skipped_idempotent)；
  超时/拦截/熔断/成本枯竭/频率灰度拦截均不算成功，但留在分母（它们是真实触发）。
- P95 延迟 = replied 行 duration_ms 升序 95 分位（nearest-rank；处理开始到终态，目标 ≤30s）。
- 日成本 = Redis quantabot:cost:{date} 厘 → 元（实时控制口径；事后核对见 reconcile_cost.py）。
- 举报率：口径 = demo0 侧人工导出（被举报 AI 回复数 / AI 总回复数），本脚本不自动采集（红线禁改 demo0）。
用法：uv run python scripts/report_metrics.py [--date 2026-09-25] [--db data/decisions.db]
"""

import argparse
import asyncio
import math
from dataclasses import dataclass, field
from datetime import UTC, date as date_type, datetime, timedelta

from quanta_bot.crosscutting import budget
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.kv import RedisKV
from quanta_bot.infra.settings import Settings, resolve_data_path

# 成功率分母排除项：未命中 @（非触发）与幂等重投（同一触发的重复送达）——不是真实独立触发
EXCLUDED_FROM_DENOMINATOR = ("skipped_not_mentioned", "skipped_idempotent")


@dataclass
class MetricsReport:
    """单日指标报告（render_markdown 的数据载体）。"""

    date: str
    decision_counts: dict[str, int] = field(default_factory=dict)
    total_rows: int = 0
    replied: int = 0
    success_rate: float = 0.0
    p50_ms: int | None = None
    p95_ms: int | None = None
    daily_cost_li: int | None = None


def success_rate(entries) -> float:
    """回复成功率（诚实口径：见模块 docstring；分母为零返回 0.0）。"""
    denominator = sum(
        1 for entry in entries if entry.decision not in EXCLUDED_FROM_DENOMINATOR
    )
    if denominator == 0:
        return 0.0
    replied = sum(1 for entry in entries if entry.decision == "replied")
    return replied / denominator


def percentile(values: list[int], pct: float) -> int | None:
    """nearest-rank 分位（升序第 ceil(pct*n) 个值；空列表 None）。

    round(..., 9) 先.snap 浮点噪声：0.95*20 在二进制浮点下为 19.000000000000004，
    直接 ceil 会取到第 20 个值——snap 后回到精确的 19。
    """
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(round(pct * len(ordered), 9)))
    return ordered[rank - 1]


async def read_daily_cost_li(settings: Settings, day: date_type) -> int | None:
    """读 Redis 日成本键（未配置 Redis 返回 None——报告缺项不炸）。"""
    if not settings.redis_url:
        return None
    kv = RedisKV(settings.redis_url, settings.redis_timeout_seconds)
    try:
        return await budget.read_cost(kv, day)
    except Exception:  # 报告边界：Redis 不可用→成本缺项（None），其余指标照常
        return None
    finally:
        await kv.aclose()


async def build_report(db_path: str, settings: Settings, day: date_type) -> MetricsReport:
    """聚合单日指标（SQLite 决策行 + Redis 成本键）。"""
    audit = SQLiteAudit(db_path)
    entries = await audit.fetch_by_date(day)
    counts: dict[str, int] = {}
    for entry in entries:
        counts[entry.decision] = counts.get(entry.decision, 0) + 1
    replied_durations = [
        entry.duration_ms for entry in entries if entry.decision == "replied" and entry.duration_ms is not None
    ]
    return MetricsReport(
        date=day.isoformat(),
        decision_counts=counts,
        total_rows=len(entries),
        replied=counts.get("replied", 0),
        success_rate=success_rate(entries),
        p50_ms=percentile(replied_durations, 0.50),
        p95_ms=percentile(replied_durations, 0.95),
        daily_cost_li=await read_daily_cost_li(settings, day),
    )


def render_markdown(report: MetricsReport) -> str:
    """渲染日报（stdout / 重定向存档两用）。"""
    cost_yuan = f"{report.daily_cost_li / 1000:.2f} 元" if report.daily_cost_li is not None else "（Redis 未配置/不可用）"
    lines = [
        f"# QuantaBot 指标日报 {report.date}",
        "",
        f"- 触发决策行：{report.total_rows}（分布：{report.decision_counts}）",
        f"- 回复成功率：{report.success_rate:.1%}（口径：replied / 全部真实触发；超时/拦截/熔断/枯竭不算成功）",
        f"- 延迟 P50/P95：{report.p50_ms}ms / {report.p95_ms}ms（目标 P95 ≤ 30000ms）",
        f"- 日 LLM 成本：{cost_yuan}（目标 ≤ 30 元）",
        "- 举报率：demo0 侧人工导出口径（被举报 AI 回复 / AI 总回复），本报告不自动采集",
    ]
    return "\n".join(lines)


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="QuantaBot 指标基线日报")
    parser.add_argument("--date", default=datetime.now(UTC).date().isoformat(), help="UTC 日期 YYYY-MM-DD")
    parser.add_argument("--db", default=None, help="decisions.db 路径（默认取 Settings.audit_db_path）")
    args = parser.parse_args(argv)
    settings = Settings()
    db_path = args.db or str(resolve_data_path(settings.audit_db_path))
    day = datetime.strptime(args.date, "%Y-%m-%d").date()
    report = await build_report(db_path, settings, day)
    print(render_markdown(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
```

- [ ] **Step 1.4: scripts/reconcile_cost.py（完整文件）**

```python
"""scripts/reconcile_cost —— Langfuse 每日成本对账（G6：Redis 实时控制 vs Langfuse 事后核对）。

口径：Redis 日累计键为实时控制依据，Langfuse generation 逐次记录为事后核对依据；
差异 >5% 输出告警项（来源：Langfuse 上报失败 WARNING 丢点、fail-open 成本写缺口）。
退出码：0=对账通过/差异≤5%；1=差异>5%（cron 告警挂点）。
用法：uv run python scripts/reconcile_cost.py [--date 2026-09-25]
"""

import argparse
import asyncio
from dataclasses import dataclass
from datetime import UTC, date as date_type, datetime, time as time_module, timedelta

from langfuse import Langfuse

from quanta_bot.crosscutting import budget
from quanta_bot.infra.kv import RedisKV
from quanta_bot.infra.settings import Settings

TOLERANCE_PCT = 5.0  # 对账容忍度（Langfuse 丢点/批量延迟口径）


@dataclass
class ReconcileResult:
    """对账结果（passed=差异在容忍内）。"""

    redis_cost_li: int
    langfuse_cost_li: int
    diff_li: int
    diff_pct: float
    passed: bool


def compare_costs(
    redis_cost_li: int, langfuse_cost_li: int, tolerance_pct: float = TOLERANCE_PCT
) -> ReconcileResult:
    """比较两侧成本（基数取较大值防除零；差异百分比超容忍即不通过）。"""
    base = max(redis_cost_li, langfuse_cost_li, 1)
    diff_li = abs(redis_cost_li - langfuse_cost_li)
    diff_pct = diff_li / base * 100
    return ReconcileResult(
        redis_cost_li=redis_cost_li,
        langfuse_cost_li=langfuse_cost_li,
        diff_li=diff_li,
        diff_pct=diff_pct,
        passed=diff_pct <= tolerance_pct,
    )


def _observation_metadata(observation) -> dict | None:
    """兼容 SDK 返回对象/字典两形态取 metadata（v4 响应形态以实测为准）。"""
    metadata = getattr(observation, "metadata", None)
    if metadata is None and isinstance(observation, dict):
        metadata = observation.get("metadata")
    return metadata if isinstance(metadata, dict) else None


def fetch_langfuse_cost_li(client: Langfuse, day: date_type) -> int:
    """累加当日 generation observations 的 cost_li（分页拉满；只认 name=generation 的 metadata）。"""
    # 时间窗=当日 UTC 全天（闭开区间）
    from_timestamp = datetime.combine(day, time_module.min, tzinfo=UTC)
    to_timestamp = from_timestamp + timedelta(days=1)
    total_cost_li = 0
    page = 1
    while True:
        # 已装 SDK 的拉取方法与分页参数以 site-packages 实际签名为准（v4：fetch_observations）；
        # 实现本函数前先读 SDK 源码确认参数名（page/from_timestamp/to_timestamp 或等价游标）。
        response = client.fetch_observations(
            from_timestamp=from_timestamp, to_timestamp=to_timestamp, page=page
        )
        observations = getattr(response, "data", response)
        if not observations:
            break
        for observation in observations:
            name = getattr(observation, "name", None) or (
                observation.get("name") if isinstance(observation, dict) else None
            )
            if name != "generation":
                continue
            metadata = _observation_metadata(observation)
            cost_li = metadata.get("cost_li") if metadata else None
            if isinstance(cost_li, int):
                total_cost_li += cost_li
        # 分页终止判定：响应对象的 has_more（或 meta.has_more——SDK 形态以实测为准）
        page_meta = getattr(response, "meta", None)
        has_more = getattr(response, "has_more", None)
        if has_more is None:
            has_more = getattr(page_meta, "has_more", False) if page_meta is not None else False
        if not has_more:
            break
        page += 1
    return total_cost_li


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Langfuse 每日成本对账")
    parser.add_argument("--date", default=datetime.now(UTC).date().isoformat(), help="UTC 日期 YYYY-MM-DD")
    args = parser.parse_args(argv)
    settings = Settings()
    day = datetime.strptime(args.date, "%Y-%m-%d").date()
    langfuse_client = Langfuse(
        public_key=settings.langfuse_public_key,
        secret_key=settings.langfuse_secret_key,
        host=settings.langfuse_host,
    )
    langfuse_cost_li = fetch_langfuse_cost_li(langfuse_client, day)
    langfuse_client.flush()
    if not settings.redis_url:
        print("redis_url 未配置——无法对账（Redis 侧缺项）")
        return 2
    kv = RedisKV(settings.redis_url, settings.redis_timeout_seconds)
    try:
        redis_cost_li = await budget.read_cost(kv, day)
    finally:
        await kv.aclose()
    result = compare_costs(redis_cost_li, langfuse_cost_li)
    print(
        f"对账 {day.isoformat()}：Redis {result.redis_cost_li} 厘 vs Langfuse {result.langfuse_cost_li} 厘"
        f"（差异 {result.diff_li} 厘 / {result.diff_pct:.1f}%，容忍 {TOLERANCE_PCT}%）"
        f"→ {'通过' if result.passed else '告警：差异超容忍'}"
    )
    return 0 if result.passed else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
```

- [ ] **Step 1.5: 批量核心测试**

Create `tests/unit/scripts/__init__.py`（若不存在，空文件）与 `tests/unit/scripts/test_report_metrics.py`：

```python
"""指标口径核心测试：成功率分母排除、nearest-rank 分位、日报聚合。"""

from datetime import UTC, date, datetime

from quanta_bot.crosscutting.ports import DecisionLogEntry
from quanta_bot.infra.audit_db import SQLiteAudit
from scripts import report_metrics


async def test_success_rate_and_p95_from_seeded_db(tmp_path) -> None:
    """诚实口径：not_mentioned/idempotent 出分母；拦截/失败留分母不算成功；P95 nearest-rank。"""
    audit = SQLiteAudit(str(tmp_path / "decisions.db"))
    today = datetime.now(UTC).date()
    rows = [
        ("replied", 100), ("replied", 200), ("replied", 300), ("replied", 400), ("replied", 500),
        ("failed", 999), ("rejected_moderation", 999), ("skipped_cost_exhausted", None),
        ("skipped_not_mentioned", 10), ("skipped_idempotent", 10), ("skipped_rate_limit", 5),
    ]
    for index, (decision, duration_ms) in enumerate(rows, start=1):
        await audit.record(
            DecisionLogEntry(
                comment_id=index,
                decision=decision,
                mode=None,
                reason="口径测试",
                created_at=datetime.now(UTC),
                duration_ms=duration_ms,
            )
        )
    entries = await audit.fetch_by_date(today)
    assert len(entries) == 11
    # 分母 = 11 - 2（排除 not_mentioned/idempotent）= 9；分子 5 → 5/9
    assert report_metrics.success_rate(entries) == 5 / 9
    durations = [
        entry.duration_ms
        for entry in entries
        if entry.decision == "replied" and entry.duration_ms is not None
    ]
    assert report_metrics.percentile(durations, 0.95) == 500  # ceil(0.95*5)=5 → 第 5 值
    assert report_metrics.percentile([], 0.95) is None


def test_percentile_nearest_rank_boundary() -> None:
    """nearest-rank 边界：20 个值 P95=第 19 个（升序）。"""
    values = list(range(1, 21))  # 1..20（乱序输入验证排序）
    shuffled = values[::-1]
    assert report_metrics.percentile(shuffled, 0.95) == 19
```

（`scripts` 可导入性：若 pyproject 未把 scripts 纳入包路径，用 `sys.path` 处理或参照既有 `tests/unit/scripts/test_run_m4_redteam.py` 的导入方式——实现时跟随该文件现状。）

Create `tests/unit/scripts/test_reconcile_cost.py`：

```python
"""对账核心测试：容忍边界、generation metadata 累加（fake client 注入）。"""

from datetime import date

from scripts import reconcile_cost


def test_compare_costs_tolerance_boundary() -> None:
    """差异 4% 过 / 6% 告警（基数取较大侧）。"""
    assert reconcile_cost.compare_costs(10000, 10400).passed is True
    assert reconcile_cost.compare_costs(10000, 10600).passed is False
    assert reconcile_cost.compare_costs(0, 0).passed is True  # 双零=基线对齐


class _FakePage:
    """模拟 SDK 分页响应形态（.data 列表 + .has_more）。"""

    def __init__(self, data: list[dict], has_more: bool) -> None:
        self.data = data
        self.has_more = has_more


class FakeLangfuse:
    """分页拉取 fake（响应带 .data/.has_more；只认 generation 的 metadata）。"""

    def __init__(self) -> None:
        self.pages = [
            _FakePage(
                [
                    {"name": "generation", "metadata": {"cost_li": 30}},
                    {"name": "span", "metadata": {"cost_li": 999}},  # 非 generation 不计
                ],
                has_more=True,
            ),
            _FakePage([{"name": "generation", "metadata": {"cost_li": 70}}], has_more=False),
        ]

    def fetch_observations(self, **kwargs):
        return self.pages.pop(0) if self.pages else _FakePage([], False)


def test_fetch_langfuse_cost_li_sums_generation_only() -> None:
    """只累计 name=generation 的 metadata.cost_li，跨页求和。"""
    total = reconcile_cost.fetch_langfuse_cost_li(FakeLangfuse(), date(2026, 9, 25))
    assert total == 100
```

- [ ] **Step 1.6: 批量验证**

```
uv run ruff format src tests scripts
uv run ruff check src tests scripts
uv run pytest tests/unit -q
```
Expected: 全绿（新增 4 测；既有 test_audit_db 若因 _Row 加字段整元组比较破坏，补 `duration_ms` 维度断言——行为变更是 M5-D 需求驱动）。ruff 若未覆盖 scripts 目录，把 `scripts` 加入 ruff 配置（pyproject `[tool.ruff]` 的显式路径）。

- [ ] **Step 1.7: Commit**

```bash
git add QuantaBot/src QuantaBot/tests QuantaBot/scripts QuantaBot/pyproject.toml
git commit -m "feat(metrics): M5-D 指标基线——duration 打点+成功率/P95 日报+Langfuse 对账脚本

影响面：decisions 表增 duration_ms 列（旧库自动迁移）；report_metrics/reconcile_cost 两脚本落档。"
```

---

### Task 2: 三场真栈演练（kill 止血 / 灰度回滚 / 成本分档）

**前置**：管理员启动 demo0 主服务；`docker compose -f QuantaBot/docker/docker-compose.yml up -d` 全栈 healthy（口径同 M3 轨道 A：评论机审开启）。`.env` 已配轻模型（Qwen key 复用 embedding 凭据；端点=dashscope compatible-mode，**演练前与管理员确认端点与模型名可用**——不可用则演练 3 记录回退主模型路径并标注待确认项）。

- [ ] **Step 2.1: 演练 1 —— kill switch 置位止血（验收①）**

操作序列（每步记录时间戳与观察点，汇总落 `eval/reports/m5-drill-killswitch.md`）：

1. 经 demo0 界面对 bot 发一条真实 @ 评论 → 确认正常回复 + SQLite decisions 落 `replied` + Langfuse trace 可查（记 trace id）。
2. `redis-cli -h 127.0.0.1 -p 6379 SET quantabot:switch:kill "true"`（agent-redis 端口按 compose 实际映射）。
3. 观察点：≤5s 内 app 日志出现消费暂停/管线短路；再发一条 @ → **期间零新回复**（decisions 表出现 `skipped_killswitch` 行=决策日志留痕证据）。
4. **幂等终验附带**：kill 期间将第 1 步同一条 comment_id 消息重投（MQ 管理界面重发或等 kill 解除后重投）→ 解除 kill 后该重投被 `skipped_idempotent` 拦截，写库零新增——幂等终验证据。
5. `redis-cli ... SET quantabot:switch:kill "false"` → 恢复消费，再发 @ 正常回复。
6. 证据文件内容：时间线（置位→暂停生效耗时实测值）、命令、decisions 表摘录、恢复验证。

- [ ] **Step 2.2: 演练 2 —— 灰度放量 + persona_version 回滚（验收②）**

1. `redis-cli ... SET quantabot:switch:graylist '[<白名单测试用户id>]'`（JSON 数组字符串）。
2. 非白名单用户发 @ → decisions 落 `skipped_graylist`；白名单用户发 @ → 正常 `replied`。
3. 回滚验证：白名单用户先正常交互 2-3 轮产生记忆（当前版本盖章）→ `redis-cli ... SET quantabot:switch:persona_version "v-m5-rollback-<date>"` → 同用户再发 @ → Langfuse trace 的 `memory_selected_ids` 为空、记忆注入区为空（**旧 persona_version 记忆检索返回空**——三重过滤生效证据）。
4. `redis-cli ... DEL quantabot:switch:graylist` 与 `DEL quantabot:switch:persona_version` → 恢复全量+本地 hash 版本。
5. 证据落 `eval/reports/m5-drill-graylist-rollback.md`（含灰度拦截/放行/回滚失效三段观察）。

- [ ] **Step 2.3: 演练 3 —— 成本分档（验收③）**

1. 记录当日日期 → `redis-cli ... SET quantabot:cost:<yyyymmdd> "28000"`（枯竭阈值）→ 发 @ → decisions 落 `skipped_cost_exhausted`、零 LLM 调用。
2. `redis-cli ... SET quantabot:cost:<yyyymmdd> "20000"`（吃紧阈值）→ 发 @ → 正常回复 + Langfuse trace `model_used=qwen3.5-plus`（轻模型）；若轻模型端点不可用 → 回退主模型路径验证（`model_used=deepseek-v4-flash` + composition WARNING）并记录待确认项。
3. `redis-cli ... DEL quantabot:cost:<yyyymmdd>` → 恢复充足档。
4. 证据落 `eval/reports/m5-drill-cost-tiers.md`（枯竭静默/吃紧切换两段 + 决策日志摘录）。

- [ ] **Step 2.4: 对账演练（附属于演练 3）**

当日已产生真实 LLM 调用后：`uv run python scripts/reconcile_cost.py` → 输出对账行；同时 `uv run python scripts/report_metrics.py` 出当日日报（四指标口径就绪证据）。两份输出贴进 `m5-drill-cost-tiers.md`。

- [ ] **Step 2.5: Commit（三份证据文件）**

```bash
git add QuantaBot/eval/reports/m5-drill-*.md
git commit -m "test(drill): M5-D 三场横切演练证据（kill 止血/灰度回滚/成本分档）+ 对账与日报演练"
```

---

### Task 3: 上线终验 + Release gate 复跑 + Maintain 闭环 + 终回写

- [ ] **Step 3.1: 终验四条件逐项核销（对照总计划 §4 汇总表）**

| 条件 | 证据来源 | 核销动作 |
|---|---|---|
| 幂等防重 | 演练 1 Step 4 重投证据（`skipped_idempotent` + 写库零新增） | 摘录进交付摘要 |
| 输出审核 | 三层：规则预检单测（`rejected_moderation`）+ 泄漏扫描单测（`leak_hits`）+ demo0 机审（M3 违规机审 fixture 证据） | 引用既有证据 |
| 决策日志 | 演练 1-3 的 SQLite decisions 摘录 + Langfuse trace id 留档 | 抽查 3 条 trace 反查 |
| 人格评测集回归 | **最终冻结 SHA Release gate 复跑**（Step 3.2） | 报告入 eval/reports |

- [ ] **Step 3.2: Release gate 复跑（最终冻结 SHA，真实费用约 1.5h）**

确认工作区干净、全部 M5 改动已合入 main，记录 `git rev-parse HEAD`：

```powershell
$env:QUANTABOT_EVAL='1'
uv run python scripts/run_m4_gate.py --runs 2 --manifest eval/gate-manifest.yaml --output-dir eval/reports
npm ci
uv run python scripts/run_m4_redteam.py --tag gate=m5-release --tag "git.sha=$(git rev-parse HEAD)"
```

Expected: 门禁退出码 0（Persona 双轮 17 场景 P0 零失败、P1/P2 ≥3/情绪 ≥4、双跑一致；红队 75/75、攻击成功数 0）。M4 校准环纪律沿用：失败轮如实留档不删，只复验失败项。

- [ ] **Step 3.3: Maintain 闭环落档**

Create `docs/plans/故障复盘模板.md`：

```markdown
# 故障复盘模板（Maintain 闭环——线上问题→修复→经验固化）

> 触发条件：线上任一 P0/P1 故障（人格事故、静默率异常、成本击穿、安全事件）或同类问题二犯。
> 纪律：复盘写入新一轮计划（Maintain→Plan 回写总计划）；坑固化为回归用例进 tests/eval 或单测，
> 不依赖记性防二犯（Anthropic AI-Native SDLC 对齐，总计划 v1.6）。

## 一、现象（用户可感知的事实，不掺推断）
## 二、影响（范围/时长/决策日志与 trace 证据链接）
## 三、根因（技术归因到代码/配置/提示词层）
## 四、修复（改动 + 验证证据：命令实际输出）
## 五、固化动作（新增回归用例/断言/门禁阈值——落到具体文件）
## 六、回写动作（总计划/技术选型/AGENTS 哪一节改了什么）

---

## 已知坑固化清单（M5 收官基线）

| 坑 | 固化状态 | 再犯触发条件 |
|---|---|---|
| waterline 同秒穿越（轨道 B） | 已固化：`pipeline-waterline` eval case + 7251fbe 修复 | 任何上下文组装时序改动后必跑 fast gate |
| flash 档随机风格违规（M4 §8） | 记录在案，不预做 | M5 后仍出现同类随机违规→写库前确定性格式护栏（与泄漏扫描同构） |
| Promptfoo resume 路径不稳（M4 遗留） | 记录在案 | Release gate 断点续跑失败时单独补测试/改绝对化路径 |
| 轻模型人格质量盲飞（M5-B 决议） | 记录在案（三层审核兜底 + kill switch 止血） | 吃紧档常态化（日报显示 tight 占比>10%）→ 补跑轻模型 Persona P0 门 |
| demo0 C-6 消费端短路 bot 二审 | 管理员已修复（102eee8，v2.4） | demo0 评论机审配置变更后复核二审链路 |
```

- [ ] **Step 3.4: 总计划终回写**

- M5 状态 `未开始→完成`（若 A/B/C 已各自回写进行中则此步改完成）；勾选剩余 checkbox：M5-kill switch全量（演练闭环）、M5-上线终验、M5-指标基线、M5-Maintain闭环；勾选 §4「M5 终验」四项。
- §7 变更记录追加 v2.5/v2.6（M5 开工与完成两条；四计划文件、三场演练、Release gate 结果、终验四条件核销）。

- [ ] **Step 3.5: AGENTS.md / 技术选型终回写**

- AGENTS.md：§3 命令区追加 `uv run python scripts/report_metrics.py` / `uv run python scripts/reconcile_cost.py`（含用途一句话）；变更记录 v0.4.7/v0.4.8（M5 落地条目）。
- 技术选型.md：§4.7 对账执行方式（手动/可挂 cron、>5% 告警、退出码口径）；§6.6 指标观测口径回填（成功率分母排除项、P95 nearest-rank、举报率人工导出）。

- [ ] **Step 3.6: 终验 Commit**

```bash
git add QuantaBot/总计划.md QuantaBot/AGENTS.md QuantaBot/docs QuantaBot/eval/reports
git commit -m "docs: M5 收官终回写——终验四条件核销/Release gate 复跑证据/Maintain 闭环落档"
```

---

## 验收标准（M5 整体收官口径）

1. `uv run pytest tests/unit tests/eval -q` 全绿（含 M5-A/B/C 新增全部用例与 eval case）；
2. kill switch 置位演练：≤5s 暂停、期间零新回复、`skipped_killswitch` 决策日志留痕（演练 1 证据文件）；
3. 人格版本回滚演练：旧 persona_version 记忆检索返回空（演练 2 证据文件）；
4. 成本键打满演练：枯竭 `skipped_cost_exhausted` / 吃紧 `model_used` 切换，决策日志可查（演练 3 证据文件）；
5. §4 汇总表「终验」列四项全勾（幂等/审核/决策日志/评测回归——回归=最终冻结 SHA Release gate 全过）；
6. report_metrics/reconcile_cost 在演练数据上跑通（口径就绪）；故障复盘模板落档；
7. 总计划/AGENTS/技术选型 回写完成。
