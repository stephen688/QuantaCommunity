# M5 Persona 校准免费验证（2026-09-27）

本记录针对 `74b6463` 首次 Persona 失败后的最小校准；校准前根仓库 HEAD 为 `8c50638`，其间两个提交仅属于其他任务的 demo0 ES8，未改变 QuantaBot。冻结实施 SHA 由随后独立工作树的付费门禁报告记录。

## 实际最终命令和输出

在 `QuantaBot/` 运行，未设置 `QUANTABOT_EVAL=1`：

```text
ruff format src tests scripts/run_m4_gate.py scripts/report_metrics.py scripts/reconcile_cost.py
7 files reformatted, 89 files left unchanged
ruff check src tests scripts/run_m4_gate.py scripts/report_metrics.py scripts/reconcile_cost.py
All checks passed!
python -B -m pytest -m "not persona" -q
306 passed, 12 skipped, 22 deselected, 2 warnings in 8.31s
```

12 skipped 为需显式外部集成开关的用例；22 deselected 是 Persona marker 排除项，不能称已运行。两项既有警告为 Starlette BlockingPortal 弃用和故意失败装配测试的 Qdrant 版本探测，非新增断言失败。

## 新回归覆盖

- 首次显式双问最多一次格式修复，返回合法第二答并汇总两次用量；连续违规或空输出不写库。
- 外部第二次调用失败保留首筆已知费用，并标记不完整；第二次异常若自带完整用量则不误报未知。
- 严格对账读取不完整旗标，仍保留已知费用但拒绝费用完整 PASS；SQLite 日报保守标记失败行待核。
- 格式能力失败不可按基础设施重跑；网络故障仍最多重试一次，失败报告保留已知估算费用。
- 独立review I-1：HTTP成功但缺少usage时传递不完整标记，二次成功也不能把未知用量补零宣称完整；3条新回归RED后窄GREEN58 passed。

RED 已在实现前分别确认：原生成直接返回双问；原对账将已知部分视作完整；旧 gate 将格式失败归为 INFRA 且失败已知费用为零。当前 GREEN 不代表人格语义质量已通过，仍需新 SHA 真模型双轮及红队。

独立只读审查已收口：C=0 / I=0 / Minor=0，可冻结。未以旧SHA291 passed或窄19/4替代本候选验证。
