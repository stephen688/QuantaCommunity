"""crosscutting/breaker —— 三态熔断器（M5：closed→open→half_open；进程内存态）。

职责：llm / memory / main_service 三份实例的失败计数、open 计时与 half_open 探测放行；
      计数单位=run（该依赖在本 run 内任一关键调用失败即 failure，走完全部关键调用即 success——
      避免同 run 内"决策成功+生成失败"互相清零导致永不熔断）。
边界：状态存进程内存（单实例单消费者架构，重启=closed 重新计数——不给熔断器自己引入 Redis 故障域）；
      不做重试（AGENTS Don't：静默优于乱回）；half_open 探测=放行下一条真实触发（单消费者串行，
      天然无并发探测竞争）；时间经 clock 参数注入（测试拨钟不 sleep）。
已知坑：record_success 在 half_open 态才迁移状态，closed 态只清零——两态语义勿混。
"""

import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Literal

BreakerState = Literal["closed", "open", "half_open"]


class BreakerOpenError(Exception):
    """熔断 open（或 half_open 探测失败回退后的新 open 期）——调用方据此静默/降级。"""


class CircuitBreaker:
    """单个依赖的熔断状态机（线程语义=单事件循环内顺序调用，无锁）。"""

    def __init__(
        self,
        name: str,
        failure_threshold: int,
        open_seconds: float,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.name = name
        self._failure_threshold = failure_threshold
        self._open_seconds = open_seconds
        self._clock = clock
        self._state: BreakerState = "closed"
        self._consecutive_failures = 0
        self._opened_at = 0.0

    @property
    def state(self) -> BreakerState:
        """当前态（open 到期不惰性迁移——迁移发生在下一次 before_call，此处只读）。"""
        return self._state

    def before_call(self) -> None:
        """调用前置判定：open 未到期拒绝；到期转 half_open 放行单条探测。"""
        if self._state == "open":
            if self._clock() - self._opened_at < self._open_seconds:
                raise BreakerOpenError(
                    f"熔断器 {self.name} 处于 open 态（冷却 {self._open_seconds}s 内）"
                )
            self._state = "half_open"  # 冷却期满：放行一条真实调用作探测

    def record_success(self) -> None:
        """成功结算：half_open 探测成功→closed；closed→清零连续失败计数。"""
        if self._state == "half_open":
            self._state = "closed"
        self._consecutive_failures = 0

    def record_failure(self) -> None:
        """失败结算：closed 累计达阈值→open；half_open 探测失败→回 open 重新计时。"""
        if self._state == "half_open":
            self._trip_open()
            return
        self._consecutive_failures += 1
        if self._consecutive_failures >= self._failure_threshold:
            self._trip_open()

    def _trip_open(self) -> None:
        self._state = "open"
        self._opened_at = self._clock()
        self._consecutive_failures = 0


@dataclass
class BreakerSet:
    """三份熔断器（llm / memory / main_service——M5 共识：PRD"审核"档由 main_service 承接）。

    字段带默认工厂（共识默认档 5 次 / 60-30-30s）：PipelineDeps.breakers 可无参缺省构造，
    既有测试/eval runner 组装零改动（closed 态全放行=行为透明）——吸收并行草案优点②。
    """

    llm: CircuitBreaker = field(default_factory=lambda: CircuitBreaker("llm", 5, 60.0))
    memory: CircuitBreaker = field(default_factory=lambda: CircuitBreaker("memory", 5, 30.0))
    main_service: CircuitBreaker = field(
        default_factory=lambda: CircuitBreaker("main_service", 5, 30.0)
    )
