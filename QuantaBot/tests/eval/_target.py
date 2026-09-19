"""评测目标适配层：向 CLI/Promptfoo 暴露完整 QuantaBot 管线执行入口。

本模块不导入 pytest 或 ``test_*.py`` 收集模块；具体组装仍复用同目录下的 runner
基础设施，避免红队与 Persona gate 形成两套行为不一致的管线。
"""

from tests.eval._runner import CaseResult, EvalCase, run_case


async def run_target_case(case: EvalCase) -> CaseResult:
    """强制真实模型执行 Persona/红队 case，并返回完整管线结果。"""
    return await run_case(case, force_real=True)
