"""infra/mq —— MQ 连通性（消费者主循环在 Task 17 的 consumer.py 落地，本模块只做 /health 探测）。

职责：check_connection 供 /health 探测 RabbitMQ 可达性。
边界：不消费消息、不声明队列（拓扑按 C-1 契约由 demo0 声明，本地集成测试自建）；失败返回 False 由调用方记 WARNING。
"""

import aio_pika


async def check_connection(url: str, timeout_seconds: float = 5.0) -> bool:
    """连上即断开（连通探测）。"""
    try:
        connection = await aio_pika.connect(url, timeout=timeout_seconds)
        await connection.close()
        return True
    except Exception:  # 探测边界：任何失败都归 False（细节不展开，/health 只关心可达性）
        return False
