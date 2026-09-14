"""memory —— 记忆层（对话级/用户级/长期级）。

职责：定义记忆读写接口（ports），供 pipeline 依赖。
边界：实现不写在本包（Redis/Qdrant 适配器在 infra）；不直接 import qdrant-client/redis。
"""
