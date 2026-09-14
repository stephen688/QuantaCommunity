"""infra —— 唯一触碰外部世界的层。

职责：settings 配置收口、MQ/主服务/向量库/Redis/SQLite/Langfuse 适配器。
边界：不写业务决策（pipeline 负责）；被 pipeline/memory/crosscutting 依赖，禁止反向依赖。
"""
