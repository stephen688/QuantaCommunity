# AI 云审核 — 第一步：环境就绪

对应实现方案「推荐顺序」第 ① 步：执行 SQL、配置密钥、开通阿里云服务。

## 1. 数据库：执行 `V_ai_moderation.sql`

目标库：`demo`（与 `application.yml` 中 `jdbc:mysql://.../demo` 一致）。

### 方式 A：CMD（推荐，避免 PowerShell 中文编码问题）

```bat
cd /d D:\download\资料\day01\后端初始工程\demo0
mysql --host=127.0.0.1 --port=3306 --user=root -p --default-character-set=utf8mb4 demo < src\main\resources\db\V_ai_moderation.sql
```

### 方式 B：PowerShell 脚本

```powershell
.\scripts\apply-v-ai-moderation.ps1 -Password "你的密码" -DbHost 127.0.0.1
```

### 验收

```sql
SHOW TABLES LIKE 'tb_moderation_record';
DESCRIBE tb_moderation_record;
```

应存在表 `tb_moderation_record`，且含唯一索引 `uk_target_provider (target_type, target_id, provider)`。

---

## 2. 本地密钥配置

### 方式 A：`application-local-secret.yml`（当前项目默认）

1. 复制模板：

   ```text
   src/main/resources/application-local-secret.yml.example
   → application-local-secret.yml
   ```

2. 在 `quanta.moderation` 下填入与 OSS 相同的 AccessKey（或单独的内容安全 RAM 用户）。

3. 确认 `application.yml` 中有：

   ```yaml
   spring.profiles.include: local-secret
   ```

### 方式 B：纯环境变量（服务器 / CI）

复制项目根目录 `.env.example` → `.env`，填入：

| 变量 | 说明 |
|------|------|
| `ALIYUN_ACCESS_KEY_ID` | 内容安全 AccessKey |
| `ALIYUN_ACCESS_KEY_SECRET` | 内容安全 Secret |
| `ALIOSS_ACCESS_KEY_ID` | OSS（图片 URL 审核依赖 OSS 可读） |
| `DB_PASSWORD` / `RABBITMQ_PASSWORD` 等 | 见 `.env.example` |

服务器部署时设置 `SPRING_PROFILES_INCLUDE=`（空），避免加载 `local-secret`。

---

## 3. 阿里云控制台（需人工操作）

登录 [内容安全控制台](https://yundun.console.aliyun.com/?p=cts) / [绿网增强版](https://green.console.aliyun.com/)。

### 3.1 开通服务

- 开通 **内容安全**（增强版 / Green 2022 API）。
- 区域与配置一致：`cn-shanghai`，Endpoint：`green-cip.cn-shanghai.aliyuncs.com`（见 `application.yml` `quanta.moderation`）。

### 3.2 与本项目代码对应的服务名

| 类型 | 代码中的 Service | 说明 |
|------|------------------|------|
| 文本 | `comment_detection`（Pro 可用时改 `comment_detection_pro`） | `AliyunTextModerationClient` |
| 图片 | `baselineCheckByVL`（classic 可用时改 `baselineCheck`） | `AliyunImageModerationClient` |

在控制台确认上述检测场景已开通/有配额；若报「service not found」或 4xx，对照控制台文档调整场景名或升级套餐。

### 3.3 RAM 权限

为使用的 AccessKey 所属用户授予（示例策略名以控制台为准）：

- 内容安全：`AliyunYundunGreenWebFullAccess` 或细化的 Green API 写权限。
- OSS：已有上传权限；**图片审核**需阿里云能访问图片 URL（公网可读或授权内容安全访问 Bucket）。

### 3.4 图片 URL 自检

Bucket：`java-ai13580089828`（`application.yml` `quanta.alioss.bucket-name`）。

- 若对象为 **私有读**：云审可能拉不到图 → 需改为临时签名 URL，或在 OSS 侧授权内容安全访问。
- 联调时用一条带图的帖子，在 `tb_moderation_record.raw_response` 中查看图片检测是否成功。

### 3.5 计费与开关（可选）

- `quanta.moderation.targets.comment.enabled=false` 时评论不走云 API（省费用），见 `application.yml`。
- 帖子/回答默认开启 AI；关闭某类时务必配置 `disabled-policy`（`PENDING` / `APPROVED`）。

---

## 4. 中间件

| 组件 | 要求 |
|------|------|
| MySQL | 已执行迁移，`demo` 库可连 |
| RabbitMQ | 与 `application.yml` 一致；应用启动时会声明审核相关队列 |
| Redis / ES | 审核通过后的曝光/搜索依赖，联调第二步再验 |

---

## 5. 第一步完成自检清单

- [ ] `tb_moderation_record` 表存在
- [ ] 本地 `application-local-secret.yml` 或环境变量已配置 `quanta.moderation` 密钥
- [ ] 阿里云内容安全已开通，文本/图片场景有配额
- [ ] RAM 用户具备 Green API 权限
- [ ] RabbitMQ 可连接

全部打勾后，进入 **第二步**：启动后端，发一条带图帖子，观察 MQ 消费与 `tb_moderation_record` 是否有记录。

---

## 附录：实名认证无法发帖

若管理端已通过实名但小程序仍提示认证：

1. 确认 `tb_user_auth.audit_status = 1`（认证表已通过）
2. 确认 `tb_user.auth_status = 2`（用户表展示态为已认证，**不是 1**）
3. 执行修复脚本：`src/main/resources/db/fix_user_auth_status_display.sql`
4. 重启后端并重新登录小程序
