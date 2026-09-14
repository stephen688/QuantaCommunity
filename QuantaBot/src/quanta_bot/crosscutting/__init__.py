"""crosscutting —— 横切纪律（全纯逻辑，重点单测）。

职责：幂等去重、熔断三态、频率约束、成本分档、kill switch 轮询、审核规则预检。
边界：不 import infra/pipeline；不建连接（依赖通过参数注入的端口抽象）。
"""
