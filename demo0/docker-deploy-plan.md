# Docker 部署 Plan（修正版）

> 基于项目实际配置修正，适用于 demo0 校友社区平台

---

## 总体分阶段（建议顺序）

1. 盘点依赖与端口
2. 设计容器网络与配置注入策略
3. 制作应用镜像（Dockerfile）
4. 编排中间件（docker-compose）
5. 初始化数据库（含 tb_moderation_record）
6. 启动与健康检查
7. 功能验收（接口 + 数据 + ES + MQ）
8. 生产加固（安全、限额、监控、备份）

---

## 详细 Plan（每步含：操作 / 产物 / 验收）

### Step 0：确定部署边界（当天）

**操作**
- 默认采用 **全栈同机**：app + mysql + redis + elasticsearch
- **RabbitMQ 保持远程**：当前项目连接 `8.163.87.228:5672`，不容器化
- AI 服务（DeepSeek/阿里云 Green）继续走公网 API，不容器化

**产物**
- 部署拓扑图（逻辑上即可）

**验收**
- 明确仅暴露 `9191`（应用），其他端口仅内网或按需开放

---

### Step 1：准备 Docker 环境（30分钟）

**操作**
- 安装 Docker Desktop / Docker Engine + Compose v2
- 校验版本：`docker --version`、`docker compose version`

**产物**
- 可用容器运行时

**验收**
- `docker run --rm hello-world` 成功

---

### Step 2：新增"容器专用配置层"（关键）

**操作**
- 新增 `application-docker.yml`（建议）
- 不改动业务配置语义，只覆盖连接地址：
  - MySQL host 改为 `mysql`
  - Redis host 改为 `redis`
  - ES host 改为 `elasticsearch`
  - **RabbitMQ 保持远程** `8.163.87.228`（不改为 rabbitmq）
- 覆盖 `spring.profiles.include` 为空，避免容器加载 `local-secret`

**application-docker.yml 示例**：
```yaml
spring:
  profiles:
    include: ""  # 覆盖 include，不加载 local-secret

  datasource:
    url: jdbc:mysql://mysql:3306/demo?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC
    username: root
    password: ${DB_PASSWORD}

  data:
    redis:
      host: redis
      port: 6379
      database: 1

  # RabbitMQ 保持远程，不覆盖
  # rabbitmq:
  #   host: 8.163.87.228

  elasticsearch:
    uris: http://elasticsearch:9200
```

**产物**
- `application-docker.yml`

**验收**
- 容器内应用日志显示已加载 docker profile 且连接目标是服务名，不是 localhost/IP

---

### Step 3：准备环境变量文件（密钥治理）

**操作**
- 基于 `.env.example` 复制生成部署用 `.env`
- 填写真实值：
  - `DB_PASSWORD`
  - `RABBITMQ_PASSWORD`（远程 RabbitMQ 密码）
  - `JWT_USER_SECRET_KEY`
  - `ALIOSS_ACCESS_KEY_ID/SECRET`
  - `ALIYUN_ACCESS_KEY_ID/SECRET`
  - `WECHAT_SECRET`
  - `OPENAI_API_KEY`
  - `QWEN_API_KEY`
- 新增中间件变量（建议）：
  - `MYSQL_DATABASE=demo`
  - `MYSQL_USER=demo_user`
  - `MYSQL_PASSWORD=...`
  - `ES_JAVA_OPTS=-Xms1g -Xmx1g`（修正：512MB 不够，改为 1GB）

**产物**
- `.env`（仅本机，禁止提交）

**验收**
- `docker compose config` 能正确解析全部变量，无空值告警

---

### Step 4：编写应用 Dockerfile（多阶段构建）

**操作**
- 阶段1：`maven:3.9-eclipse-temurin-17` 编译打包
- 阶段2：`eclipse-temurin:17-jre` 仅运行 jar
- JVM 参数建议：`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75`
- **创建向量存储目录**：`RUN mkdir -p /app/data/vector-store`（对应 RAG 向量文件）

**Dockerfile 示例**：
```dockerfile
# 阶段1：编译
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 阶段2：运行
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/target/demo0.jar app.jar
RUN mkdir -p /app/data/vector-store
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

**产物**
- `Dockerfile`

**验收**
- `docker build -t demo0-backend:latest .` 成功
- 镜像启动可读取 `SPRING_PROFILES_ACTIVE=docker`

---

### Step 5：编排 docker-compose（核心）

**操作**
- 新建 `docker-compose.yml`，服务建议如下：
  - `mysql`（8.0）
  - `redis`（7）
  - `elasticsearch`（7.12.1，`discovery.type=single-node`）
  - **不编排 RabbitMQ**（保持远程连接）
  - `demo0-app`（你的 Dockerfile 构建）
- 所有服务加入同一 network：`demo0-net`
- **depends_on + healthcheck**：app 依赖中间件健康后再起
- 持久化 volume：
  - `mysql_data`
  - `redis_data`
  - `es_data`
  - `app_data`（映射到 `/app/data`，包含向量存储）

**docker-compose.yml 示例**：
```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: demo0-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
      MYSQL_DATABASE: demo
    ports:
      - "3306:3306"  # 仅调试时开放，生产注释掉
    volumes:
      - mysql_data:/var/lib/mysql
      - ./src/main/resources/db:/docker-entrypoint-initdb.d  # 初始化脚本
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - demo0-net

  redis:
    image: redis:7-alpine
    container_name: demo0-redis
    ports:
      - "6379:6379"  # 仅调试时开放，生产注释掉
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - demo0-net

  elasticsearch:
    image: elasticsearch:7.12.1
    container_name: demo0-es
    environment:
      - discovery.type=single-node
      - "ES_JAVA_OPTS=${ES_JAVA_OPTS:--Xms1g -Xmx1g}"  # 修正：1GB
    ports:
      - "9200:9200"  # 仅调试时开放，生产注释掉
    volumes:
      - es_data:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9200/_cluster/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
    deploy:
      resources:
        limits:
          memory: 2g  # 容器内存限制
    networks:
      - demo0-net

  demo0-app:
    build: .
    container_name: demo0-app
    ports:
      - "9191:9191"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_PROFILES_INCLUDE=  # 覆盖 include，不加载 local-secret
      - DB_PASSWORD=${DB_PASSWORD}
      - RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
      - JWT_USER_SECRET_KEY=${JWT_USER_SECRET_KEY}
      - ALIOSS_ACCESS_KEY_ID=${ALIOSS_ACCESS_KEY_ID}
      - ALIOSS_ACCESS_KEY_SECRET=${ALIOSS_ACCESS_KEY_SECRET}
      - ALIYUN_ACCESS_KEY_ID=${ALIYUN_ACCESS_KEY_ID}
      - ALIYUN_ACCESS_KEY_SECRET=${ALIYUN_ACCESS_KEY_SECRET}
      - WECHAT_SECRET=${WECHAT_SECRET}
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - QWEN_API_KEY=${QWEN_API_KEY}
    volumes:
      - app_data:/app/data  # 向量存储持久化
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      elasticsearch:
        condition: service_healthy
    networks:
      - demo0-net

volumes:
  mysql_data:
  redis_data:
  es_data:
  app_data:

networks:
  demo0-net:
    driver: bridge
```

**产物**
- `docker-compose.yml`

**验收**
- `docker compose up -d` 后 4 个容器均为 `healthy/running`

---

### Step 6：数据库初始化策略（必做）

**操作**
- 使用 MySQL init 机制（推荐）：
  - 将 `src/main/resources/db/` 下的 SQL 脚本挂载到 `/docker-entrypoint-initdb.d/`
  - **全量脚本优先**：`demo-全量.sql`（包含所有表结构）
  - **增量脚本**：`V_ai_moderation.sql`（创建 tb_moderation_record）
  - **种子数据**：`seed-batch-20260526.sql`（测试数据，可选）
- 若已有旧库，则改成"手动执行迁移脚本"模式，避免重复初始化风险

**产物**
- 初始化脚本挂载策略

**验收**
- `tb_moderation_record` 存在，且唯一索引 `uk_target_provider` 存在
- 所有业务表（tb_user、tb_content、tb_content_comment 等）已创建

---

### Step 7：首启顺序与参数（精确执行）

**操作**
1. `docker compose pull`（拉基础镜像）
2. `docker compose build demo0-app`
3. `docker compose up -d mysql redis elasticsearch`
4. 等健康检查通过（`docker compose ps` 查看状态）
5. `docker compose up -d demo0-app`

**产物**
- 首次可用环境

**验收**
- `docker compose ps` 显示全部正常
- 应用日志无"连接拒绝/认证失败"
- `docker compose logs -f demo0-app` 查看启动日志

---

### Step 8：连通性验收（按项目现成脚本）

**操作**
- 使用 `scripts/verify_integrations.py` 做 smoke test
- 验证项覆盖：
  - 登录 `/user/login`
  - C 端回答列表
  - 管理端分页接口
  - ES 内容检索数据
  - DB 状态检查

**产物**
- 验证报告（PASS/FAIL）

**验收**
- 脚本整体通过；失败项清零

---

### Step 9：中间件参数调优（第二天）

**操作**
- MySQL：`max_connections`、字符集 `utf8mb4`
- Redis：开启 AOF（可选）
- ES：堆内存固定 + 容量阈值预警
- **RabbitMQ 远程连接**：确认网络连通性、超时配置

**产物**
- `docker-compose.override.yml`（调优层，可选）

**验收**
- 压测 10~30 分钟，服务无明显抖动/重启

---

### Step 10：生产安全加固（上线前必须）

**操作**
- 不对公网暴露 `3306/6379/9200`（仅 `9191` 暴露）
- 注释掉 docker-compose 中的端口映射（MySQL/Redis/ES）
- `.env` 权限最小化（只 root 可读）
- JWT 长密钥更换为高强度随机串
- **RabbitMQ 远程连接**：确认防火墙规则、IP 白名单

**产物**
- 安全基线配置

**验收**
- 外网端口扫描只有预期端口开放

---

### Step 11：发布与回滚机制（上线策略）

**操作**
- 发布：
  - `docker compose build demo0-app`
  - `docker compose up -d demo0-app`（仅滚动 app）
- 回滚：
  - 保留上一版本镜像 tag
  - `docker compose up -d demo0-app` 指向旧 tag

**产物**
- 发布 SOP 文档

**验收**
- 回滚演练一次成功（5分钟内恢复）

---

### Step 12：备份与灾备（必须落地）

**操作**
- MySQL：每日 `mysqldump` + 保留7~14天
- ES：按规模决定 snapshot（小规模可先跳过，至少保留重建脚本）
- Redis：关键在持久化 volume + 主机级快照
- **向量库**：备份 `data/vector-store/content-vector-store.json`（app_data volume）

**产物**
- 定时任务与备份目录规范

**验收**
- 任意一天可恢复到可用状态

---

## 推荐的 Compose 服务映射（目标状态）

| 服务 | 端口映射 | 说明 |
|------|---------|------|
| demo0-app | `9191:9191` | 唯一对外暴露端口 |
| mysql | 内网（调试时临时 `3306:3306`） | 生产注释掉 |
| redis | 内网（调试时临时 `6379:6379`） | 生产注释掉 |
| elasticsearch | 内网（调试时临时 `9200:9200`） | 生产注释掉 |
| RabbitMQ | **远程** `8.163.87.228:5672` | 不容器化 |

---

## 关键注意点（避免踩坑）

1. **Profile 加载机制**：`application.yml` 默认 `spring.profiles.include=local-secret`，容器里必须用环境变量 `SPRING_PROFILES_INCLUDE=""` 覆盖，或创建 `application-docker.yml` 覆盖 include

2. **ES 版本对齐**：当前 ES 版本依赖是 `7.12.1`，容器镜像要严格对齐，避免客户端协议不兼容

3. **RAG 向量存储路径**：本地文件 `./data/vector-store/content-vector-store.json`，必须挂 volume `app_data:/app/data`，不然容器重建后丢失

4. **容器网络内用服务名**：RabbitMQ/Redis/MySQL/ES 在容器网络内必须用服务名（`mysql`、`redis`、`elasticsearch`），不能再写 `127.0.0.1`

5. **RabbitMQ 远程连接**：当前项目连接远程服务器 `8.163.87.228`，容器内应用需确保能访问外网，或改用容器化 RabbitMQ

6. **审核链路依赖**：`tb_moderation_record` 表初始化遗漏会导致后续审核链路异常，确保 `V_ai_moderation.sql` 已执行

7. **ES 内存配置**：ES 7.12.1 默认堆内存 1GB，不要设为 512MB，可能导致 OOM

---

## 修正记录

| 修正项 | 原 Plan | 修正后 | 原因 |
|--------|---------|--------|------|
| RabbitMQ | 容器化 | **保持远程** `8.163.87.228` | 项目当前配置连接远程服务器 |
| Profile 覆盖 | 设 `spring.profiles.include` 为空 | 用环境变量 `SPRING_PROFILES_INCLUDE=""` 覆盖 | `include` 是叠加配置，需显式覆盖 |
| ES 内存 | `ES_JAVA_OPTS=-Xms512m -Xmx512m` | `ES_JAVA_OPTS=-Xms1g -Xmx1g` + 容器限制 2GB | 512MB 可能导致 OOM |
| 数据库初始化 | 只挂载 `V_ai_moderation.sql` | 挂载全量 SQL + 增量脚本 | 需要所有业务表结构 |
| 健康检查 | 未给出具体配置 | 加 MySQL/ES healthcheck + depends_on condition | ES 和 MySQL 启动较慢，需等待就绪 |
| 向量存储 | 未提目录创建 | Dockerfile 加 `RUN mkdir -p /app/data/vector-store` | 避免启动时报目录不存在 |
