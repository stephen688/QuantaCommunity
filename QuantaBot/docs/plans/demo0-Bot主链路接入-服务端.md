# demo0 Bot 服务端主链路接入实施计划（Task 1-10）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Phase 0 敲定的七项契约（C-1~C-7）落到 demo0 主服务侧，完成 D1/D2/D4/D5/D6/D7 及 QuantaBot 墓碑消费，为小程序和真联调提供稳定接口。

**Architecture:** demo0 以最小侵入接入 bot：新增 `controller/bot/` 只读命名空间与 `quantabot.exchange` MQ 拓扑，复用现有 Outbox / 阿里云机审 / JWT 认证三大基建，bot 身份 = 固定 user_id 10000 的系统账号 + `BOT` 角色 + 一年期 service token。QuantaBot 侧在本计划只补内容同步墓碑消费；小程序、用户可见改名和真联调由后续计划消费本计划的接口。

**Tech Stack:** demo0 = Java 17 / Spring Boot 3.5 / MyBatis / RabbitMQ / Redis / PageHelper；QuantaBot = Python 3.12 / FastAPI / aio-pika / Pydantic / Qdrant；小程序 = TypeScript 原生微信小程序。

**Spec:** `QuantaBot/docs/plans/Phase0-契约谈判.md`（C-1~C-7 契约与 D1-D7 清单的唯一权威）；`QuantaBot/总计划.md`（里程碑结构）；`QuantaBot/AGENTS.md`（红线与工作流）。

---

## 1. 需求结论（grill 两轮决策快照，2026-09-17）

| # | 决策点 | 结论 |
|---|---|---|
| G-昵称 | bot 对外昵称 | **框框**（系统名仍为 QuantaBot：代码/队列/Redis 键/环境变量前缀均不改） |
| G-token | service token 有效期与吊销 | dev 脚本签发 **1 年期 JWT**（复用 `JwtUtil.createJWT`）；泄露处置 = 换 `JWT_USER_SECRET_KEY` 环境变量后重签（全量 token 失效的代价已接受） |
| G-限流 | bot 评论限流 | 独立档 **scene=`comment-send-bot`，6 次/60 秒**（普通用户保持 10/60s 不变） |
| G-标识 | AI 标识实现 | 用户维度 `BOT` 角色（user_role 表 seed）+ 评论 VO 透出 `isBot` 布尔（前端渲染 AI 角标） |
| G-删除 | 内容同步删除语义 | sync 返回**墓碑记录**（`status="deleted"`，docId 与原记录一致）；QuantaBot 摄取时对墓碑调 Qdrant 删点 |
| G-批次 | 实施结构 | **本计划三批 Tranche**：批1 demo0 地基（D1+D2+D7）→ 批2 触发链路（D4）→ 批3 读取与同步（D5+D6，两侧）；D3/改名/真联调转后续计划 |
| G-环境 | 联调环境 | QuantaBot `.env` 直连 `192.168.100.128:5672`（虚拟机现役 MQ，凭据同 demo0 application.yml：admin/`${RABBITMQ_PASSWORD}`/vhost `/`）；Redis 共 localhost:6379，demo0 用 db1、**QuantaBot 用 db2**；Qdrant/Langfuse 用 QuantaBot 自带 compose |
| G-验收 | 验收口径 | 工程行为全验（幂等重投/kill 短路/机审驳回不可见/树分页拉满/记忆写入/泄漏替换/sync 墓碑）+ 行为抽样 6-8 代表场景 + 其余 `QUANTABOT_EVAL=1`；demo0 新接口进 api-test `.http` 套件 + Service 层单测；**不加 CI** |
| G-政策 | 政策文档内容 | 批3 交付前管理员提供 5-10 条核心政策；**缺则降级口径验收场景 6**（RAG 命中政策类断言放宽为"检索链路联通"） |

**明确不做**（与本计划无关）：bot 主动触发（Phase 2）、demo0 换正式向量库、跨服务分布式事务、CI 流水线。

---

## 2. Global Constraints（每个 Task 隐含继承）

1. **契约权威**：字段名/枚举值/拓扑名与 `Phase0-契约谈判.md` 逐字对齐。冲突时以契约为准并在任务里回报，不得擅自改契约。
2. **botTriggerKind 值域为小写字符串** `"mentioned"` / `"replied"`（QuantaBot `TriggerEvent.trigger_kind: Literal["mentioned","replied"]` 是硬闸门，大写会变毒丸）。
3. **`commentImages` 字段永不为 null**：demo0 发事件时无图也必须发 `[]`（Pydantic `tuple[str,...]` 收到 null 直接校验失败 → 消息被丢弃）。
4. **系统名 QuantaBot 不改**：模块/包名、`QUANTABOT_` 环境变量前缀、`quantabot:` Redis 键、`quantabot.comment.queue` 队列名、文档里的项目名，全部保持；只有**用户可见昵称/徽章/人格文案**改为「框框」。
5. **demo0 改动仅限本计划授权落点**：新增 `controller/bot/`、`vo/bot/`、`service/bot/`（或 Impl 内新方法）、`mq/message/BotMentionMessage.java`，以及各 Task 精确列出的既有文件行级修改。禁止顺手重构无关代码。
6. **数据库只做增量幂等改动**：seed/建表必须可重复执行（`CREATE TABLE IF NOT EXISTS` + `INSERT ... SELECT ... WHERE NOT EXISTS`，沿用 `dev-seed-incremental.sql` 惯例）。
7. **bot 写库单一入口**：bot 发评论只走现有 `POST /comment/send`（C-5/C-6 契约，审计路径唯一），不建 `/bot/comment/send` 写接口。
8. **bot 只见合规内容**：mention 事件只挂在"审核通过"钩子（`approveComment` / `approveRejectedComment`），驳回/待审评论不产生事件。
9. **验证命令**：demo0 侧 `mvn -f demo0/pom.xml test -Dtest="XxxTest"`（demo0 无 mvnw，用系统 mvn）；QuantaBot 侧在 `QuantaBot/` 目录用 `uv run pytest tests/unit -q` / `uv run ruff format src tests` + `uv run ruff check src tests`；提交前三件套全绿。
10. **机审总闸语义**：bot 评论强制进 AI 机审只豁免 `targets.comment.enabled=false` 分区开关，**仍尊重全局 `quanta.moderation.enabled` 总开关**（总闸关闭时 bot 也不机审，走 disabled-policy）。
11. **红线 §0.3/§0.5**（AGENTS.md）：bot 侧失败静默不回但必须留痕；写库必须走主服务 HTTP 入口，禁止直连数据库。
12. **bot 评论落库不可绕过机审可见**：C-6 失败语义 = 机审驳回 → bot 回复不可见（现有链路自动处理）。

---

## 3. 关键既有代码事实（执行者必读，均已核实）

| 事实 | 位置/证据 |
|---|---|
| 认证链：JWT 解析 → `validateCurrentSession`（Redis `login:token:{userId}` 相等校验）→ 封禁 → 用户存在 → verified → loadRoles | `demo0/src/main/java/com/quanta/demo0/security/TokenAuthenticationServiceImpl.java:57-122` |
| loadRoles 只接受 `RolePermissionMapping.isManagementRole()` 认可的 user_role 角色（否则 warn 丢弃） | `demo0/src/main/java/com/quanta/demo0/security/TokenAuthenticationServiceImpl.java:215-261`；`security/RolePermissionMapping.java:32-38` |
| 角色常量值**无 ROLE_ 前缀**（`USER`/`VERIFIED_USER`/`SUPER_ADMIN`），Spring 前缀转换在 `OptionalJwtAuthenticationFilter.buildGrantedAuthorities` | `constant/RoleConstants.java`；`security/OptionalJwtAuthenticationFilter.java:199-248` |
| user_role 表列：`user_id, role_code, created_by`（**无 create_time**） | `mapper/UserRoleMapper.java:40-51` |
| 过滤器把 header 值**原样**传 authenticate（不剥 Bearer）；header 名 = `jwt.user-token-name: authorization` | `OptionalJwtAuthenticationFilter.java:75-99`；`application.yml:119-123` |
| JWT 工具为静态方法 `JwtUtil.createJWT(secretKey, ttlMillis, claims)` / `parseJWT`（HS256） | `utils/JwtUtil.java` |
| 评论发送：`@RateLimit(scene="comment-send", limit=10, windowSeconds=60)` + `@PreAuthorize("hasRole('VERIFIED_USER')")` | `controller/user/CommentController.java:33-39` |
| 限流切面以 `AuthenticatedUser.getUserId()` 为主体调 `rateLimitService.check(scene, userId, limit, window, failClosed)` | `aop/RateLimitAspect.java:31-68` |
| `shouldModerateComment()` = `moderationProperties.isEnabled() && targets.comment.enabled`；当前 yml：全局 true、comment.enabled=false、disabled-policy=APPROVED | `service/Impl/CommentServiceImpl.java:244-262`；`application.yml:148-169` |
| 审核通过钩子两处（PENDING→APPROVED 与 REJECTED→APPROVED），副作用链：incrementCommentCounts → createCommentNotificationEvents → createHotScoreRecalculateEvent → createCommentSearchEvents | `service/Impl/CommentAuditServiceImpl.java:54-79, 107-125` |
| 评论图片查询已有 `selectImagesByCommentId(commentId) -> List<String>` | `mapper/CommentMapper.java:151` |
| Outbox 事件创建模式（UUID eventId → builder 消息 → serializePayload → validatePayloadSize → insert 失败抛异常回滚） | `service/Impl/OutboxEventServiceImpl.java:329-371`（createCommentModerationEvent） |
| Outbox 路由模式：`resolve()` 内按 eventType 分支 + `deserializePayload(payload, X.class)` + `OutboxRoute.builder().exchange().routingKey().message()` | `mq/outbox/OutboxRouteRegistry.java:28+` |
| MQ 拓扑命名模式：`<域>.<动作>.exchange/.queue/.retry.queue/.dlx.queue`，DirectExchange + durable + retry 队列 `x-message-ttl=60000` + DLX 回主队列 | `config/RabbitMQConfig.java`（全文件） |
| QuantaBot 消费者**被动消费**（`channel.get_queue(ensure=False)`，不声明拓扑）——demo0 必须声明 quantabot 队列 | `QuantaBot/src/quanta_bot/consumer.py:27,56` |
| QuantaBot MainServiceClient 发 `Authorization: Bearer {token}` 头 | `QuantaBot/src/quanta_bot/infra/main_service.py:40` |
| TriggerEvent 契约字段（camelCase alias）与 botTriggerKind 小写枚举 | `QuantaBot/src/quanta_bot/pipeline/trigger.py:18-50` |
| C-2 三接口的客户端调用形状：`GET /bot/comment/chain?commentId=`、`GET /bot/comment/history?userId=&postId=&pageNum=&pageSize=`、`GET /bot/comment/tree?postId=&pageNum=&pageSize=&sortType=asc`（分页拉满 total） | `QuantaBot/src/quanta_bot/infra/main_service.py:74-134` |
| SyncDoc 契约形状（docId/docKind/contentId/answerId/title/content/createTime/updateTime/updatedAt）与摄取编排 `ingest_content` | `QuantaBot/src/quanta_bot/infra/content_sync.py:19-176` |
| QuantaBot 实际 Redis 键与 C-7 契约完全一致：`quantabot:switch:kill/graylist/persona_version`、`quantabot:cost:` | `crosscutting/killswitch.py:20-22`、`crosscutting/budget.py:13` |
| 评论树现状：`CommentPageVO.list` 是 `List<Map<String,Object>>` 弱类型（C-2② 需新建强类型 VO 给 bot） | `service/Impl/CommentServiceImpl.java` commentPage |
| seed 幂等模式：`INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS` | `demo0/src/main/resources/db/dev-seed-incremental.sql` |
| eval 两档：`uv run pytest tests/eval`（pipeline 档零成本）与 `QUANTABOT_EVAL=1 uv run pytest tests/eval`（persona 档真调 DeepSeek） | `QuantaBot/AGENTS.md:74` |
| RabbitMQ 连接：`192.168.100.128:5672`，admin / `${RABBITMQ_PASSWORD}` / vhost `/` | `demo0/src/main/resources/application.yml:32-37` |

---

## 4. 批次结构（三批 Tranche）

| 批 | Task | 内容 | 依赖 |
|---|---|---|---|
| 批1 demo0 地基 | Task 1 | QuantabotProperties + BOT 角色 + 系统账号 seed（D1 前半） | — |
| | Task 2 | service token 认证特判 + Bearer 剥离 + token 生成器（D1 核心） | Task 1 |
| | Task 3 | bot 强制机审 + 限流分档 + isBot 透出（D2 + C-5 限流/标识） | Task 1 |
| | Task 4 | RedisConstants 控制面键段（D7） | — |
| 批2 触发链路 | Task 5 | BOT_MENTION_REQUESTED 枚举 + BotMentionMessage + createBotMentionEvent（D4 前半） | Task 1 |
| | Task 6 | BotMentionDetector + 审核通过双挂载（D4 核心） | Task 5 |
| | Task 7 | quantabot MQ 拓扑 + Outbox 路由分支（D4 后半） | Task 5 |
| 批3 读取与同步 | Task 8 | /bot/comment/{chain,history,tree} 三只读接口（D5） | Task 2 |
| | Task 9 | /bot/content/sync + 政策文档表（D6 demo0 侧） | Task 2 |
| | Task 10 | QuantaBot 同步墓碑消费（D6 bot 侧） | Task 9（联调；代码可先行） |

---

## 批1 demo0 地基（D1+D2+D7）

### Task 1: QuantabotProperties + BOT 角色 + bot 系统账号 seed

**Files:**
- Create: `demo0/src/main/java/com/quanta/demo0/properties/QuantabotProperties.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/constant/RoleConstants.java`（加 BOT 常量）
- Modify: `demo0/src/main/java/com/quanta/demo0/security/RolePermissionMapping.java`（ROLE_PERMISSIONS 注册 BOT）
- Modify: `demo0/src/main/resources/application.yml`（加 quantabot 配置段）
- Modify: `demo0/src/main/resources/db/dev-seed-incremental.sql`（末尾追加 seed 段）
- Test: `demo0/src/test/java/com/quanta/demo0/security/RolePermissionMappingBotTest.java`

**Interfaces:**
- Produces: `QuantabotProperties`（`getBotUserId(): Long`、`getBotNickname(): String`）——Task 2/3/6 直接注入使用；`RoleConstants.BOT = "BOT"`；user_role 表中 `user_id=10000, role_code='BOT'` 的种子行。

- [x] **Step 1: 写失败测试**

```java
package com.quanta.demo0.security;

import com.quanta.demo0.constant.RoleConstants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BOT 角色注册验证（C-5 系统账号契约）：
 * BOT 必须能从 user_role 表进入角色集合，但不携带任何管理权限。
 */
class RolePermissionMappingBotTest {

    @Test
    void bot是系统支持的角色_可从user_role进入角色集合() {
        assertTrue(RolePermissionMapping.isManagementRole(RoleConstants.BOT));
    }

    @Test
    void bot角色不带任何管理权限() {
        Set<String> permissions =
                RolePermissionMapping.permissionsFor(Set.of(RoleConstants.BOT));
        assertTrue(permissions.isEmpty());
    }

    @Test
    void bot不影响现有管理角色的权限计算() {
        Set<String> withBot = RolePermissionMapping.permissionsFor(
                Set.of(RoleConstants.BOT, RoleConstants.CONTENT_AUDITOR));
        Set<String> withoutBot = RolePermissionMapping.permissionsFor(
                Set.of(RoleConstants.CONTENT_AUDITOR));
        assertEquals(withoutBot, withBot);
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="RolePermissionMappingBotTest"`
Expected: FAIL——`bot是系统支持的角色_可从user_role进入角色集合` 断言失败（isManagementRole("BOT") 当前返回 false）。

- [x] **Step 3: RoleConstants 加 BOT 常量**

在 `RoleConstants.java` 的 `SUPER_ADMIN` 常量之后追加：

```java
    /**
     * QuantaBot 系统账号（服务间鉴权专用）。
     * 不携带任何管理权限，仅用于标识 bot 身份（契约 C-5）。
     */
    public static final String BOT = "BOT";
```

- [x] **Step 4: RolePermissionMapping 注册 BOT（无权限）**

在 `ROLE_PERMISSIONS = Map.of(` 中（`SUPER_ADMIN` 条目之后）追加一个条目（Map.of 支持 10 组内 KV，当前 3 组 +1 无碍）：

```java
            ,

            /*
             * QuantaBot 系统账号：
             * 服务间鉴权身份（C-5 契约），无任何管理权限，
             * 注册进来只为让 user_role 表中的 BOT 角色能通过
             * isManagementRole 校验进入 Spring Security。
             */
            RoleConstants.BOT,
            Set.of()
```

- [x] **Step 5: 跑测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="RolePermissionMappingBotTest"`
Expected: PASS（3 个用例全绿）。

- [x] **Step 6: 新建 QuantabotProperties**

先看一眼 `demo0/src/main/java/com/quanta/demo0/properties/JwtProperties.java` 的头部注册方式（`@Component` 还是 `@EnableConfigurationProperties` 挂在主类），**用同样的方式**注册下面这个类：

```java
package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * QuantaBot 接入配置（契约 C-5/C-6）。
 *
 * bot 系统账号固定 user_id=10000（与 dev-seed-incremental.sql 种子一致）；
 * 昵称「框框」是 @ 文本检测与前端展示的统一口径。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quantabot")
public class QuantabotProperties {

    /** bot 系统账号 user_id（seed 固定 10000） */
    private Long botUserId = 10000L;

    /** bot 昵称（@ 卡片插入文本、mention 文本检测口径） */
    private String botNickname = "框框";
}
```

- [x] **Step 7: application.yml 加配置段**

在 `application.yml` 的 `jwt:` 段之后追加（与 jwt 同级缩进）：

```yaml
# QuantaBot 接入配置（C-5/C-6 契约；bot 系统账号见 dev-seed-incremental.sql）
quantabot:
  bot-user-id: 10000
  bot-nickname: 框框
```

- [x] **Step 8: seed 追加 bot 系统账号**

在 `dev-seed-incremental.sql` 文件末尾追加：

```sql
-- -----------------------------------------------------------------------------
-- 13. QuantaBot 系统账号（C-5 契约：固定 user_id=10000，昵称=框框，不走微信登录）
--     auth_status=0（bot 不做实名认证，verified=false；评论入口由 BOT 角色放行）
--     avatar_url 留空，小程序用默认头像兜底；联调时可手动 UPDATE 换正式头像
-- -----------------------------------------------------------------------------
INSERT INTO tb_user (
    id, openid, nick_name, avatar_url, auth_status, account_status,
    is_admin, is_deleted, create_time, update_time
)
SELECT
    10000, NULL, '框框', NULL, 0, 0,
    0, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_user WHERE id = 10000);

INSERT INTO user_role (user_id, role_code, created_by)
SELECT 10000, 'BOT', 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM user_role WHERE user_id = 10000 AND role_code = 'BOT'
);
```

- [x] **Step 9: 执行 seed 前核对表结构**

Run（MySQL 客户端，库=demo）:
```bash
mysql -h127.0.0.1 -P3306 -uroot -p demo -e "DESCRIBE tb_user; DESCRIBE user_role;"
```
核对：`tb_user` 上述 10 列名存在且除 id/nick_name 外均可空或有默认值（`openid` 可空已由 UserMapper.xml 动态 insert 佐证）；若存在其他 NOT NULL 无默认列（如 `app_user_id`），把它以 `NULL` 或合理默认值补进 Step 8 的 INSERT 列清单后再执行。`user_role` 三列与 UserRoleMapper.grantRole 的 insert 列一致。

- [x] **Step 10: 执行 seed 并验证**

```bash
mysql -h127.0.0.1 -P3306 -uroot -p demo < demo0/src/main/resources/db/dev-seed-incremental.sql
mysql -h127.0.0.1 -P3306 -uroot -p demo -e "SELECT id, nick_name, auth_status, account_status, is_deleted FROM tb_user WHERE id = 10000; SELECT user_id, role_code FROM user_role WHERE user_id = 10000;"
```
Expected：一行 `10000 / 框框 / 0 / 0 / 0`；一行 `10000 / BOT`。重复执行整份 seed 不报错、不产生重复行（幂等）。

- [ ] **Step 11: 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test`
Expected: 全绿（现有测试 + 新增 3 个用例）。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/properties/QuantabotProperties.java src/main/java/com/quanta/demo0/constant/RoleConstants.java src/main/java/com/quanta/demo0/security/RolePermissionMapping.java src/main/resources/application.yml src/main/resources/db/dev-seed-incremental.sql src/test/java/com/quanta/demo0/security/RolePermissionMappingBotTest.java
git -C demo0 commit -m "feat(bot): QuantabotProperties 配置与 BOT 角色注册 + 框框系统账号 seed（D1，C-5）"
```
（若 demo0 不是独立 git 仓库，去掉 `-C demo0` 在仓库根提交，路径加 `demo0/` 前缀；下同。）

---

### Task 2: service token 认证特判 + Bearer 前缀剥离 + token 生成器

**Files:**
- Modify: `demo0/src/main/java/com/quanta/demo0/constant/JwtClaimsConstant.java`（加 TOKEN_TYPE 常量）
- Modify: `demo0/src/main/java/com/quanta/demo0/security/TokenAuthenticationServiceImpl.java`（claims 解析重构 + service token 特判）
- Modify: `demo0/src/main/java/com/quanta/demo0/security/OptionalJwtAuthenticationFilter.java`（Bearer 剥离）
- Test: `demo0/src/test/java/com/quanta/demo0/security/TokenAuthenticationServiceImplBotTokenTest.java`
- Test: `demo0/src/test/java/com/quanta/demo0/security/OptionalJwtAuthenticationFilterBearerTest.java`
- Create: `demo0/src/test/java/com/quanta/demo0/devtools/BotServiceTokenGeneratorTest.java`（手动运行的 dev 工具）

**Interfaces:**
- Consumes: Task 1 的 `QuantabotProperties`、`RoleConstants.BOT`、seed 的 user_role 行。
- Produces: 认证服务接受 `tokenType=service` 且 `userId=10000` 的 JWT（免 Redis 会话校验，角色含 BOT）；HTTP 头 `Authorization: Bearer <token>` 与裸 `<token>` 双形态兼容；`BotServiceTokenGeneratorTest` 可打印一年期 service token（Task 13 的 `.env` 用）。

- [x] **Step 1: JwtClaimsConstant 加常量**

```java
package com.quanta.demo0.constant;

public class JwtClaimsConstant {


    public static final String USER_ID = "userId";

    /**
     * 令牌类型 claim 键（C-5 service token 特判依据）。
     */
    public static final String TOKEN_TYPE = "tokenType";

    /**
     * 服务间令牌类型值：bot 系统账号专用，免 Redis 会话校验。
     */
    public static final String SERVICE_TOKEN_TYPE = "service";

}
```

- [x] **Step 2: 写失败测试（认证特判）**

```java
package com.quanta.demo0.security;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.utils.JwtUtil;
import org.junit.jupiter.api.After;
import org.junit.jupiter.api.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * service token 认证特判（C-5）：
 * bot 的一年期 service token 无 Redis 登录态，必须跳过 validateCurrentSession；
 * 特判仅对 bot 系统账号开放，普通用户伪造 tokenType 也绕不过。
 */
@ExtendWith(MockitoExtension.class)
class TokenAuthenticationServiceImplBotTokenTest {

    /** HS256 要求密钥 >= 32 字节 */
    private static final String SECRET = "test-secret-key-0123456789abcdef0123456789abcdef";

    @Mock
    private JwtProperties jwtProperties;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;

    @InjectMocks
    private TokenAuthenticationServiceImpl service;

    private final QuantabotProperties quantabotProperties = new QuantabotProperties();

    @Before
    void setUp() {
        when(jwtProperties.getUserSecretKey()).thenReturn(SECRET);
        // Redis 无任何会话/封禁/认证缓存（service token 场景的关键前置）
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);

        ReflectionTestUtils.setField(service, "quantabotProperties", quantabotProperties);
    }

    @After
    void tearDown() {
        // 无状态服务，无需清理
    }

    private String serviceToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        claims.put(JwtClaimsConstant.TOKEN_TYPE, JwtClaimsConstant.SERVICE_TOKEN_TYPE);
        return JwtUtil.createJWT(SECRET, 60_000L, claims);
    }

    private User botUser() {
        User user = new User();
        user.setId(10000L);
        user.setNickName("框框");
        user.setAccountStatus(0);
        return user;
    }

    @Test
    void serviceToken免Redis会话校验_角色含BOT() {
        when(userMapper.getById(10000L)).thenReturn(botUser());
        when(userMapper.getUserAuthByUserId(10000L)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(10000L)).thenReturn(List.of("BOT"));

        AuthenticatedUser authenticated = service.authenticate(serviceToken(10000L));

        assertEquals(10000L, authenticated.getUserId());
        assertTrue(authenticated.getRoles().contains("BOT"));
        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getVerified());
    }

    @Test
    void serviceToken用于非bot账号_拒绝() {
        when(userMapper.getById(1L)).thenReturn(null); // 不会走到，特判在前

        TokenAuthenticationException exception = assertThrows(
                TokenAuthenticationException.class,
                () -> service.authenticate(serviceToken(1L))
        );
        assertEquals(TokenAuthenticationFailureReason.TOKEN_INVALID, exception.getReason());
    }

    @Test
    void 普通token无Redis会话_仍报会话失效() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, 10000L);
        String normalToken = JwtUtil.createJWT(SECRET, 60_000L, claims);

        TokenAuthenticationException exception = assertThrows(
                TokenAuthenticationException.class,
                () -> service.authenticate(normalToken)
        );
        assertEquals(TokenAuthenticationFailureReason.SESSION_NOT_FOUND, exception.getReason());
    }
}
```

（当前 `User` 实体提供 `setId`、`setNickName`、`setAccountStatus`，`TokenAuthenticationFailureReason` 枚举名也已确认；上方测试可直接使用这些成员。）

- [x] **Step 3: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="TokenAuthenticationServiceImplBotTokenTest"`
Expected: FAIL——`serviceToken免Redis会话校验_角色含BOT` 抛 SESSION_NOT_FOUND（当前实现无条件走 validateCurrentSession）。

- [x] **Step 4: 改造 TokenAuthenticationServiceImpl**

注入 `QuantabotProperties`，并把 `parseUserId` 重构为 `parseClaims` + `extractUserId`。`authenticate` 方法头部（原第 57-69 行区域）改为：

```java
    @Autowired
    private QuantabotProperties quantabotProperties;

    @Override
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_MISSING,
                    "未携带登录凭证"
            );
        }

        // 1. 校验 JWT 并取得用户 ID 与令牌类型
        Claims claims = parseClaims(token);
        Long userId = extractUserId(claims);

        // 2. service token（C-5 契约）：仅限 bot 系统账号，跳过 Redis 会话校验。
        //    普通用户携带 tokenType=service 一律拒绝，防止会话校验被绕过。
        boolean serviceToken = JwtClaimsConstant.SERVICE_TOKEN_TYPE
                .equals(claims.get(JwtClaimsConstant.TOKEN_TYPE));
        if (serviceToken) {
            if (!quantabotProperties.getBotUserId().equals(userId)) {
                throw authenticationFailed(
                        TokenAuthenticationFailureReason.TOKEN_INVALID,
                        "service token 仅限 bot 系统账号"
                );
            }
        } else {
            // 3. 校验 Redis 中的当前有效 Token
            validateCurrentSession(userId, token);
        }

        // 以下（封禁/用户存在/verified/loadRoles）保持原逻辑不变
        ...
```

原 `parseUserId` 方法整体替换为：

```java
    /**
     * 解析 JWT 全部声明。
     */
    private Claims parseClaims(String token) {
        try {
            return JwtUtil.parseJWT(
                    jwtProperties.getUserSecretKey(),
                    token
            );
        } catch (ExpiredJwtException exception) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_EXPIRED,
                    "登录凭证已过期"
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_INVALID,
                    "登录凭证无效"
            );
        }
    }

    /**
     * 从声明中提取用户 ID。
     */
    private Long extractUserId(Claims claims) {
        Object userIdClaim = claims.get(JwtClaimsConstant.USER_ID);
        if (userIdClaim == null) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_INVALID,
                    "JWT 缺少 userId"
            );
        }
        try {
            return Long.valueOf(userIdClaim.toString());
        } catch (NumberFormatException exception) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_INVALID,
                    "JWT userId 非法"
            );
        }
    }
```

- [x] **Step 5: 跑认证测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="TokenAuthenticationServiceImplBotTokenTest"`
Expected: PASS（3 个用例全绿）。

- [x] **Step 6: 写失败测试（过滤器 Bearer 剥离）**

```java
package com.quanta.demo0.security;

import com.quanta.demo0.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.After;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Bearer 前缀剥离（联调校准点）：
 * QuantaBot MainServiceClient 发 "Authorization: Bearer <token>"，
 * 小程序发裸 token——过滤器必须两种形态都原样传裸 token 给认证服务。
 */
@ExtendWith(MockitoExtension.class)
class OptionalJwtAuthenticationFilterBearerTest {

    @Mock
    private JwtProperties jwtProperties;
    @Mock
    private TokenAuthenticationService tokenAuthenticationService;
    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private OptionalJwtAuthenticationFilter filter;

    @After
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/bot/comment/chain");
        request.addHeader("authorization", headerValue);
        return request;
    }

    @Test
    void bearer前缀被剥离后传给认证服务() throws Exception {
        org.mockito.Mockito.when(jwtProperties.getUserTokenName()).thenReturn("authorization");

        filter.doFilter(
                request("Bearer abc.def.ghi"),
                new MockHttpServletResponse(),
                filterChain
        );

        verify(tokenAuthenticationService).authenticate(eq("abc.def.ghi"));
    }

    @Test
    void 裸token原样传给认证服务() throws Exception {
        org.mockito.Mockito.when(jwtProperties.getUserTokenName()).thenReturn("authorization");

        filter.doFilter(
                request("abc.def.ghi"),
                new MockHttpServletResponse(),
                filterChain
        );

        verify(tokenAuthenticationService).authenticate(eq("abc.def.ghi"));
    }
}
```

（`authenticate` mock 默认返回 null 会走 filter 的空指针兜底分支？——不会：filter 对 authenticate 抛 `TokenAuthenticationException` 才清身份，正常返回后取 `authenticatedUser.getUserId()` 会 NPE 进兜底 catch。为避免 NPE 噪音，给 mock 打桩返回一个最小 `AuthenticatedUser.builder().userId(1L).roles(Set.of()).build()`。在两个用例里补 `org.mockito.Mockito.when(tokenAuthenticationService.authenticate(org.mockito.ArgumentMatchers.anyString())).thenReturn(AuthenticatedUser.builder().userId(1L).roles(java.util.Set.of()).build());`。）

- [x] **Step 7: 跑过滤器测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="OptionalJwtAuthenticationFilterBearerTest"`
Expected: FAIL——`bearer前缀被剥离后传给认证服务` 收到的是 `"Bearer abc.def.ghi"`。

- [x] **Step 8: 过滤器剥 Bearer**

在 `OptionalJwtAuthenticationFilter.doFilterInternal` 中，取 header 之后（原第 78-79 行）插入：

```java
            String token =
                    request.getHeader(headerName);

            /*
             * 兼容 Bearer 方案（QuantaBot MainServiceClient 发
             * "Authorization: Bearer <token>"；小程序发裸 token 不受影响）。
             */
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring("Bearer ".length()).trim();
            }
```

- [x] **Step 9: 跑过滤器测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="OptionalJwtAuthenticationFilterBearerTest"`
Expected: PASS（2 个用例）。

- [x] **Step 10: 写 token 生成器（dev 工具，不进默认测试集）**

```java
package com.quanta.demo0.devtools;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

/**
 * dev 工具：生成 bot 一年期 service token（grill 决议）。
 *
 * 手动运行：
 *   mvn -f demo0/pom.xml test -Dtest="BotServiceTokenGeneratorTest" -Dbot.service-token.generate=true
 *   必须显式传 -Dbot.service-token.generate=true，默认回归不执行。
 * 输出的 token 写入 QuantaBot/.env 的 QUANTABOT_MAIN_SERVICE_TOKEN。
 *
 * 泄露处置（grill 决议）：更换 JWT_USER_SECRET_KEY 环境变量后重签——
 * 代价是全体用户 token 同时失效，属可接受口径。
 */
@SpringBootTest
class BotServiceTokenGeneratorTest {

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private QuantabotProperties quantabotProperties;

    @Test
    @EnabledIfSystemProperty(named = "bot.service-token.generate", matches = "true")
    void generateBotServiceToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, quantabotProperties.getBotUserId());
        claims.put(JwtClaimsConstant.TOKEN_TYPE, JwtClaimsConstant.SERVICE_TOKEN_TYPE);

        // 1 年期（毫秒）
        long oneYearMillis = 365L * 24 * 60 * 60 * 1000;
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                oneYearMillis,
                claims
        );

        System.out.println();
        System.out.println("==== BOT SERVICE TOKEN（1 年期，userId=10000）====");
        System.out.println(token);
        System.out.println("=================================================");
    }
}
```

- [x] **Step 11: 生成 token 并人工留档**

用下面的显式开关手动运行一次（默认回归不会执行），把输出的 token 存到本地私密位置（`QuantaBot/.env`，不提交到 git）：

```bash
mvn -f demo0/pom.xml test -Dtest="BotServiceTokenGeneratorTest" -Dbot.service-token.generate=true
```

同时验证：用该 token 调 demo0 任一 authenticated 接口（api-test 环境里 07 用例会系统性覆盖，此处先手工 curl 一次 `/comment/list?contentId=10&pageNum=1&pageSize=5`，header `Authorization: Bearer <token>`）。
Expected: HTTP 200 且 data 非空（bot 通过认证，isAuthenticated() 接口可用）。

- [ ] **Step 12: 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test`
Expected: 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/constant/JwtClaimsConstant.java src/main/java/com/quanta/demo0/security/TokenAuthenticationServiceImpl.java src/main/java/com/quanta/demo0/security/OptionalJwtAuthenticationFilter.java src/test/java/com/quanta/demo0/security/TokenAuthenticationServiceImplBotTokenTest.java src/test/java/com/quanta/demo0/security/OptionalJwtAuthenticationFilterBearerTest.java src/test/java/com/quanta/demo0/devtools/BotServiceTokenGeneratorTest.java
git -C demo0 commit -m "feat(bot): service token 免会话特判（仅限 bot 账号）+ 过滤器 Bearer 前缀剥离 + 一年期 token 生成器（D1/C-5）"
```

---

### Task 3: bot 评论强制机审 + 限流独立分档 + 评论 VO 透出 isBot

**Files:**
- Modify: `demo0/src/main/java/com/quanta/demo0/annotation/RateLimit.java`（加 botLimit 属性）
- Modify: `demo0/src/main/java/com/quanta/demo0/aop/RateLimitAspect.java`（BOT 角色分档）
- Modify: `demo0/src/main/java/com/quanta/demo0/controller/user/CommentController.java`（sendComment 注解）
- Modify: `demo0/src/main/java/com/quanta/demo0/dto/CommentAddDTO.java`（加 mentionBot 字段）
- Modify: `demo0/src/main/java/com/quanta/demo0/service/Impl/CommentServiceImpl.java`（shouldModerateComment bot 分支 + isBot 透出 + mentionBot 日志）
- Test: `demo0/src/test/java/com/quanta/demo0/service/Impl/CommentServiceImplBotModerationTest.java`
- Test: `demo0/src/test/java/com/quanta/demo0/aop/RateLimitAspectBotLimitTest.java`

**Interfaces:**
- Consumes: Task 1 的 `QuantabotProperties`。
- Produces: bot 发评论时限流 scene 自动变为 `comment-send-bot`（6 次/60s）；bot 评论无视 `targets.comment.enabled=false` 强制进 AI 机审；`/comment/list`、`/comment/replyList` 响应 Map 新增 `isBot` 键（Task 11 前端渲染 AI 角标的数据源）；`CommentAddDTO.mentionBot`（Boolean，可选——C-4 前端标记，服务端以文本+replyUserId 判定为准，DTO 字段仅记录）。

- [x] **Step 1: 写失败测试（机审特判）**

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import org.junit.jupiter.api.After;
import org.junit.jupiter.api.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C-6 双层审核：bot 评论强制进 AI 机审。
 * 只豁免 targets.comment.enabled 分区开关，仍尊重全局总闸。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceImplBotModerationTest {

    @Mock
    private AliyunModerationProperties moderationProperties;
    @Mock
    private AliyunModerationProperties.Targets targets;
    @Mock
    private AliyunModerationProperties.TargetConfig commentConfig;

    @InjectMocks
    private CommentServiceImpl service;

    private final QuantabotProperties quantabotProperties = new QuantabotProperties();

    @Before
    void setUp() {
        ReflectionTestUtils.setField(service, "quantabotProperties", quantabotProperties);
        // 默认形态 = 当前 application.yml：全局开、评论分区关
        org.mockito.Mockito.when(moderationProperties.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(moderationProperties.getTargets()).thenReturn(targets);
        org.mockito.Mockito.when(targets.getComment()).thenReturn(commentConfig);
        org.mockito.Mockito.when(commentConfig.isEnabled()).thenReturn(false);
    }

    @After
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void bot评论_分区关闭仍强制机审() {
        BaseContext.setCurrentId(10000L);
        assertTrue(service.shouldModerateComment());
    }

    @Test
    void 普通用户评论_分区关闭不机审() {
        BaseContext.setCurrentId(3L);
        assertFalse(service.shouldModerateComment());
    }

    @Test
    void 普通用户评论_分区开启机审() {
        BaseContext.setCurrentId(3L);
        org.mockito.Mockito.when(commentConfig.isEnabled()).thenReturn(true);
        assertTrue(service.shouldModerateComment());
    }

    @Test
    void 全局总闸关闭_bot也不机审() {
        BaseContext.setCurrentId(10000L);
        org.mockito.Mockito.when(moderationProperties.isEnabled()).thenReturn(false);
        assertFalse(service.shouldModerateComment());
    }
}
```

（当前实现的内部类就是 `AliyunModerationProperties.Targets` 与 `AliyunModerationProperties.TargetConfig`；`CommentServiceImpl` 通过 `getTargets().getComment().isEnabled()` 读取开关，测试按这条调用链建立 Mockito mock。）

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="CommentServiceImplBotModerationTest"`
Expected: FAIL——`bot评论_分区关闭仍强制机审` 期望 true 实际 false；且 `shouldModerateComment` 可能是 private 编译不过（本步先把方法可见性改掉见 Step 3）。

- [x] **Step 3: CommentServiceImpl 改造**

注入属性 + 改 `shouldModerateComment` 为 package-private + 加 `isBotUser`：

```java
    @Autowired
    private QuantabotProperties quantabotProperties;
```

```java
    /** 全局开关 + 评论类型开关均开启时才走 AI 审核；bot 评论除外（C-6：bot 强制机审） */
    boolean shouldModerateComment() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        // C-6 契约：bot 来源评论不受 targets.comment.enabled=false 影响，强制进 AI 机审
        if (isBotUser(BaseContext.getCurrentId())) {
            return true;
        }
        AliyunModerationProperties.TargetConfig commentConfig = getCommentTargetConfig();
        return commentConfig != null && commentConfig.isEnabled();
    }

    /** C-6：判定评论作者是否 bot 系统账号 */
    boolean isBotUser(Long userId) {
        return userId != null && userId.equals(quantabotProperties.getBotUserId());
    }
```

- [x] **Step 4: 跑机审测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="CommentServiceImplBotModerationTest"`
Expected: PASS（4 个用例）。

- [x] **Step 5: 写失败测试（限流分档）**

```java
package com.quanta.demo0.aop;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.security.AuthenticatedUser;
import com.quanta.demo0.service.RateLimitService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.After;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C-5 限流分档：BOT 角色走独立 scene（comment-send-bot）与独立配额（botLimit）。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectBotLimitTest {

    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private RateLimitDecision allowedDecision;

    @InjectMocks
    private RateLimitAspect aspect;

    private final RateLimit annotation = new RateLimit() {
        @Override public String scene() { return "comment-send"; }
        @Override public int limit() { return 10; }
        @Override public int windowSeconds() { return 60; }
        @Override public boolean failClosed() { return true; }
        @Override public int botLimit() { return 6; }
    };

    @After
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(long userId, String... roles) {
        AuthenticatedUser user = AuthenticatedUser.builder()
                .userId(userId)
                .roles(Set.of(roles))
                .build();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private RateLimit annotationWithoutBotLimit() {
        return new RateLimit() {
            @Override public String scene() { return "comment-report"; }
            @Override public int limit() { return 5; }
            @Override public int windowSeconds() { return 60; }
            @Override public boolean failClosed() { return true; }
            @Override public int botLimit() { return -1; }
        };
    }

    @Test
    void bot用户走botScene与botLimit() throws Throwable {
        authenticate(10000L, "USER", "BOT");
        when(rateLimitService.check(eq("comment-send-bot"), eq("10000"), eq(6), eq(60), eq(true)))
                .thenReturn(allowedDecision);
        when(allowedDecision.isAllowed()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.checkRateLimit(joinPoint, annotation);

        assertSame("ok", result);
        verify(rateLimitService).check("comment-send-bot", "10000", 6, 60, true);
    }

    @Test
    void 普通用户仍走原scene与limit() throws Throwable {
        authenticate(3L, "USER", "VERIFIED_USER");
        when(rateLimitService.check(eq("comment-send"), eq("3"), eq(10), eq(60), eq(true)))
                .thenReturn(allowedDecision);
        when(allowedDecision.isAllowed()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.checkRateLimit(joinPoint, annotation);

        verify(rateLimitService).check("comment-send", "3", 10, 60, true);
    }

    @Test
    void 未配置botLimit时不分档() throws Throwable {
        authenticate(10000L, "USER", "BOT");
        when(rateLimitService.check(eq("comment-report"), eq("10000"), eq(5), eq(60), eq(true)))
                .thenReturn(allowedDecision);
        when(allowedDecision.isAllowed()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.checkRateLimit(joinPoint, annotationWithoutBotLimit());

        verify(rateLimitService).check("comment-report", "10000", 5, 60, true);
    }
}
```

- [x] **Step 6: 跑限流测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="RateLimitAspectBotLimitTest"`
Expected: 编译失败——`RateLimit` 注解没有 `botLimit()` 属性。

- [x] **Step 7: 扩展 RateLimit 注解与切面**

`RateLimit.java` 追加属性：

```java
    /**
     * BOT 角色独立配额（次/窗口）。
     * -1 = 不区分（默认，BOT 与普通用户同档）；
     * >= 0 时 BOT 角色自动改用 scene + "-bot" 与本配额（C-5 契约）。
     */
    int botLimit() default -1;
```

`RateLimitAspect.checkRateLimit` 在「② 用用户ID做限流主体」之前插入分档逻辑：

```java
        // ①.5 C-5 限流分档：BOT 角色且注解配置了独立配额时，
        //     scene 追加 "-bot" 后缀并改用 botLimit（普通用户不受影响）
        String scene = rateLimit.scene();
        int limit = rateLimit.limit();
        if (rateLimit.botLimit() >= 0
                && authenticatedUser.getRoles() != null
                && authenticatedUser.getRoles().contains(RoleConstants.BOT)) {
            scene = scene + "-bot";
            limit = rateLimit.botLimit();
        }

        // ② 用用户ID做限流主体，执行原子限流（scene/limit 换用上面的局部变量）
        RateLimitDecision decision = rateLimitService.check(
                scene,
                String.valueOf(authenticatedUser.getUserId()),
                limit,
                rateLimit.windowSeconds(),
                rateLimit.failClosed()
        );
```

并在切面 import 区补 `import com.quanta.demo0.constant.RoleConstants;`。

- [x] **Step 8: 跑限流测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="RateLimitAspectBotLimitTest"`
Expected: PASS（3 个用例）。

- [x] **Step 9: CommentController 挂分档注解并放行 BOT 角色**

`sendComment` 方法注解改为（C-5：bot 走现有单一入口；BOT 角色放行替代 VERIFIED_USER 门槛）：

```java
    @RateLimit(
            scene = "comment-send",
            limit = 10,
            windowSeconds = 60,
            botLimit = 6
    )
    @PreAuthorize("hasRole('" + RoleConstants.VERIFIED_USER + "') "
            + "or hasRole('" + RoleConstants.BOT + "')")
    @PostMapping("/send")
```

- [x] **Step 10: CommentAddDTO 加 mentionBot 字段 + sendComment 记日志**

`CommentAddDTO.java` 在 `imageUrls` 字段后追加：

```java
    /**
     * 是否通过 @ 卡片提及 bot（C-4 前端结构化标记）。
     * 可选；服务端事件判定以文本 @昵称 + replyUserId 为准，本字段仅作观测记录。
     */
    private Boolean mentionBot;
```

`CommentServiceImpl.sendComment` 在「5.插入评论表」之前加一行观测日志：

```java
        // C-4：前端 @ 卡片标记仅作观测留痕（事件侧判定见 CommentAuditServiceImpl）
        if (Boolean.TRUE.equals(commentAddDTO.getMentionBot())) {
            log.info("评论携带 bot mention 标记，userId={}，contentId={}", userId, contentId);
        }
```

- [x] **Step 11: 评论分页 VO 透出 isBot**

`CommentServiceImpl.commentPage` 中，一级评论 `firstMap` 构造处（grep 定位 `put("nickName"` 的位置，同一段 Map 构造内）追加：

```java
                     // C-4：AI 标识透出（前端渲染"AI"角标的依据）
                     firstMap.put("isBot", isBotUser(first.getUserId()));
```

二级回复的 Map 构造处（当前是 `buildReplyList` 的 `for (ContentComment reply : replies)`）同样追加：

```java
                    replyMap.put("isBot", isBotUser(reply.getUserId()));
```

（当前一级循环变量是 `first`，二级循环变量是 `reply`；`replyPage` 复用 `buildReplyList`，因此只需在该 helper 加一次 `replyMap.put`，不要重复插入。）

- [ ] **Step 12: 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test`
Expected: 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/annotation/RateLimit.java src/main/java/com/quanta/demo0/aop/RateLimitAspect.java src/main/java/com/quanta/demo0/controller/user/CommentController.java src/main/java/com/quanta/demo0/dto/CommentAddDTO.java src/main/java/com/quanta/demo0/service/Impl/CommentServiceImpl.java src/test/java/com/quanta/demo0/service/Impl/CommentServiceImplBotModerationTest.java src/test/java/com/quanta/demo0/aop/RateLimitAspectBotLimitTest.java
git -C demo0 commit -m "feat(bot): bot 评论强制 AI 机审 + 限流独立分档 comment-send-bot(6/60s) + 评论 VO 透出 isBot（D2/C-5/C-6）"
```

---

### Task 4: RedisConstants 控制面键命名段（D7 / C-7）

**Files:**
- Modify: `demo0/src/main/java/com/quanta/demo0/constant/RedisConstants.java`
- Test: `demo0/src/test/java/com/quanta/demo0/constant/RedisConstantsQuantabotTest.java`

**Interfaces:**
- Produces: `RedisConstants.QUANTABOT_SWITCH_KILL_KEY` 等四个常量。**注意 C-7 契约原文明确定位：「demo0 不实现 bot 控制面逻辑——键归 bot 所有，demo0 仅约定命名空间不冲突」**。本 Task 只做命名契约的代码化记载（QuantaBot 侧 `killswitch.py:20-22`、`budget.py:13` 的键值已与之完全一致），demo0 不读写这些键。

- [x] **Step 1: 写失败测试**

```java
package com.quanta.demo0.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C-7 键命名契约的代码化记载：
 * 键值必须与 QuantaBot 侧 crosscutting/killswitch.py、budget.py 的常量逐字一致。
 */
class RedisConstantsQuantabotTest {

    @Test
    void 控制面键与C7契约一致() {
        assertEquals("quantabot:switch:kill", RedisConstants.QUANTABOT_SWITCH_KILL_KEY);
        assertEquals("quantabot:switch:graylist", RedisConstants.QUANTABOT_SWITCH_GRAYLIST_KEY);
        assertEquals("quantabot:switch:persona_version",
                RedisConstants.QUANTABOT_SWITCH_PERSONA_VERSION_KEY);
        assertEquals("quantabot:cost:", RedisConstants.QUANTABOT_COST_KEY_PREFIX);
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="RedisConstantsQuantabotTest"`
Expected: 编译失败——常量不存在。

- [x] **Step 3: RedisConstants 追加段落**

在 `RedisConstants.java` 类末尾（`USER_FOLLOWER_RANK_KEY` 之后）追加：

```java
    /*
     * ===== QuantaBot 控制面命名空间（C-7 契约）=====
     *
     * 这些键归 QuantaBot 所有（读写均在 bot 侧，Redis db2）：
     *   - quantabot:switch:kill            kill switch，true=暂停消费
     *   - quantabot:switch:graylist        灰度白名单（SET of userId）
     *   - quantabot:switch:persona_version 人格版本（string）
     *   - quantabot:cost:{yyyyMMdd}        日累计成本
     *
     * demo0 不实现 bot 控制面逻辑，仅在常量层固化命名契约，
     * 保证未来 demo0 侧任何 Redis 使用不会侵入 quantabot: 命名空间。
     * 键值必须与 QuantaBot crosscutting/killswitch.py、budget.py 逐字一致。
     */
    public static final String QUANTABOT_SWITCH_KILL_KEY = "quantabot:switch:kill";
    public static final String QUANTABOT_SWITCH_GRAYLIST_KEY = "quantabot:switch:graylist";
    public static final String QUANTABOT_SWITCH_PERSONA_VERSION_KEY =
            "quantabot:switch:persona_version";
    public static final String QUANTABOT_COST_KEY_PREFIX = "quantabot:cost:";
```

- [ ] **Step 4: 跑测试确认通过 + 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test -Dtest="RedisConstantsQuantabotTest"` → PASS；
Run: `mvn -f demo0/pom.xml test` → 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/constant/RedisConstants.java src/test/java/com/quanta/demo0/constant/RedisConstantsQuantabotTest.java
git -C demo0 commit -m "feat(bot): RedisConstants 固化 quantabot 控制面键命名契约（D7/C-7）"
```

---

## 批2 触发链路（D4）

### Task 5: BOT_MENTION_REQUESTED 事件类型 + BotMentionMessage + Outbox 事件创建

**Files:**
- Modify: `demo0/src/main/java/com/quanta/demo0/enums/OutboxEventType.java`
- Create: `demo0/src/main/java/com/quanta/demo0/mq/message/BotMentionMessage.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/service/OutboxEventService.java`（接口加方法）
- Modify: `demo0/src/main/java/com/quanta/demo0/service/Impl/OutboxEventServiceImpl.java`（实现）
- Test: `demo0/src/test/java/com/quanta/demo0/mq/message/BotMentionMessageSerializationTest.java`
- Test: `demo0/src/test/java/com/quanta/demo0/service/Impl/OutboxEventServiceImplBotMentionTest.java`

**Interfaces:**
- Consumes: `OutboxEventServiceImpl` 现有 `createCommentModerationEvent` 的全套私有设施（`serializePayload` / `validatePayloadSize` / `outboxEventMapper`）。
- Produces: `OutboxEventType.BOT_MENTION_REQUESTED`；`BotMentionMessage`（字段见 Step 3，**`commentImages` 永非 null**——无图发 `[]`，否则 QuantaBot Pydantic 校验失败变毒丸）；`OutboxEventService.createBotMentionEvent(ContentComment comment, List<String> imageUrls, String botTriggerKind) -> String eventId`（Task 6 挂载点调用）。

- [x] **Step 1: OutboxEventType 加枚举**

```java
    /**
     * 审核通过的评论命中 bot（@ 或直接回复），请求 QuantaBot 触发处理（C-1）。
     */
    BOT_MENTION_REQUESTED("BOT_MENTION_REQUESTED");
```

- [x] **Step 2: 写失败测试（消息序列化形状）**

```java
package com.quanta.demo0.mq.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C-1 消息体形状闸门：
 * 字段名 camelCase 与 QuantaBot TriggerEvent alias 逐字对齐；
 * commentImages / mentionedBot 永不为 null（Pydantic 硬闸门）。
 */
class BotMentionMessageSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 全字段序列化_camelCase键名与契约一致() throws Exception {
        BotMentionMessage message = BotMentionMessage.builder()
                .eventId("evt-1")
                .eventType("BOT_MENTION_REQUESTED")
                .occurredAt(LocalDateTime.of(2026, 9, 18, 10, 0, 0))
                .retryCount(0)
                .commentId(100L)
                .postId(10L)
                .answerId(null)
                .commenterUserId(3L)
                .commentContent("@框框 帮我看看这个问题")
                .commentImages(List.of())
                .mentionedBot(true)
                .botTriggerKind("mentioned")
                .parentId(null)
                .replyCommentId(null)
                .build();

        String json = objectMapper.writeValueAsString(message);

        assertTrue(json.contains("\"eventId\":\"evt-1\""));
        assertTrue(json.contains("\"commentId\":100"));
        assertTrue(json.contains("\"postId\":10"));
        assertTrue(json.contains("\"commenterUserId\":3"));
        assertTrue(json.contains("\"commentContent\""));
        assertTrue(json.contains("\"commentImages\":[]"));
        assertTrue(json.contains("\"mentionedBot\":true"));
        assertTrue(json.contains("\"botTriggerKind\":\"mentioned\""));
    }

    @Test
    void botTriggerKind值域为小写() {
        // QuantaBot TriggerEvent.trigger_kind: Literal["mentioned","replied"]
        assertEquals("mentioned", "mentioned");
        assertEquals("replied", "replied");
    }
}
```

- [x] **Step 3: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="BotMentionMessageSerializationTest"`
Expected: 编译失败——`BotMentionMessage` 不存在。

- [x] **Step 4: 新建 BotMentionMessage**

```java
package com.quanta.demo0.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * bot 触发事件消息（C-1 契约）。
 *
 * 消费方：QuantaBot CommentEventConsumer（pipeline/trigger.py TriggerEvent，
 * camelCase alias 直收，Pydantic 校验即契约闸门）。
 *
 * 硬性约束：
 * 1. commentImages 必须非 null（无图传空列表）——null 会让 Pydantic 校验失败变毒丸；
 * 2. botTriggerKind 只允许小写 "mentioned" / "replied"；
 * 3. mentionedBot 必须非 null（TriggerEvent 必填字段）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotMentionMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Outbox 生成的事件唯一 ID（消费幂等辅助键；QuantaBot 幂等主键=commentId） */
    private String eventId;

    /** 固定为 BOT_MENTION_REQUESTED */
    private String eventType;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;

    /** 消费者重试次数（首发恒 0） */
    private Integer retryCount;

    /** 触发评论 ID */
    private Long commentId;

    /** 帖子 ID（=contentId） */
    private Long postId;

    /** 专业区回答 ID（可空） */
    private Long answerId;

    /** 触发评论作者 */
    private Long commenterUserId;

    /** 评论全文（<=500 字） */
    private String commentContent;

    /** 评论图片 URL（可空列表，永不为 null） */
    private List<String> commentImages;

    /** demo0 结构化 @ 标记（恒 true——只推命中 bot 的评论） */
    private Boolean mentionedBot;

    /** mentioned=卡片/文本 @；replied=直接回复 bot 评论 */
    private String botTriggerKind;

    /** 触发评论父级 ID（可空） */
    private Long parentId;

    /** 触发评论的被回复对象 ID（可空） */
    private Long replyCommentId;
}
```

- [x] **Step 5: 跑序列化测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="BotMentionMessageSerializationTest"`
Expected: PASS。

- [x] **Step 6: 写失败测试（Outbox 事件创建）**

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.enums.OutboxEventType;
import com.quanta.demo0.enums.OutboxEventStatus;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.mq.message.BotMentionMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createBotMentionEvent 事务性创建（照 createCommentModerationEvent 模式）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxEventServiceImplBotMentionTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @InjectMocks
    private OutboxEventServiceImpl service;

    private ContentComment comment() {
        return ContentComment.builder()
                .commentId(100L)
                .contentId(10L)
                .answerId(null)
                .parentId(null)
                .replyCommentId(null)
                .replyUserId(null)
                .userId(3L)
                .content("@框框 学长好")
                .build();
    }

    @Test
    void 创建事件_类型与聚合信息正确() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        String eventId = service.createBotMentionEvent(comment(), List.of(), "mentioned");

        assertNotNull(eventId);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();

        assertEquals(OutboxEventType.BOT_MENTION_REQUESTED.getCode(), event.getEventType());
        assertEquals("COMMENT", event.getAggregateType());
        assertEquals(100L, event.getAggregateId());
        assertEquals(OutboxEventStatus.PENDING.getCode(), event.getStatus());
        assertTrue(event.getPayload().contains("\"mentionedBot\":true"));
        assertTrue(event.getPayload().contains("\"commentImages\":[]"));
        assertTrue(event.getPayload().contains("\"botTriggerKind\":\"mentioned\""));
    }

    @Test
    void 图片列表null时消息仍带空数组() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        service.createBotMentionEvent(comment(), null, "replied");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        assertTrue(captor.getValue().getPayload().contains("\"commentImages\":[]"));
    }

    @Test
    void 插入失败抛异常_由调用方事务回滚() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.createBotMentionEvent(comment(), List.of(), "mentioned"));
    }
}
```

- [x] **Step 7: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="OutboxEventServiceImplBotMentionTest"`
Expected: 编译失败——接口/实现缺 `createBotMentionEvent`。

- [x] **Step 8: 实现接口与实现方法**

`OutboxEventService` 接口加：

```java
    /**
     * 创建 bot 触发事件（C-1）：审核通过且命中 bot 的评论，
     * 写 BOT_MENTION_REQUESTED Outbox 事件（与业务同一事务提交）。
     *
     * @param comment        触发评论（审核已通过）
     * @param imageUrls      评论图片 URL（可空——消息内会归一为空列表）
     * @param botTriggerKind 小写 "mentioned" / "replied"
     * @return eventId
     */
    String createBotMentionEvent(
            ContentComment comment,
            List<String> imageUrls,
            String botTriggerKind
    );
```

`OutboxEventServiceImpl` 照 `createCommentModerationEvent`（329-371 行）同款模式实现：

```java
    @Override
    public String createBotMentionEvent(
            ContentComment comment,
            List<String> imageUrls,
            String botTriggerKind
    ) {
        // C-1 硬约束：commentImages 永非 null（无图发空数组，防 Pydantic 毒丸）
        List<String> safeImageUrls =
                imageUrls == null ? List.of() : List.copyOf(imageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        BotMentionMessage message = BotMentionMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .retryCount(0)
                .commentId(comment.getCommentId())
                .postId(comment.getContentId())
                .answerId(comment.getAnswerId())
                .commenterUserId(comment.getUserId())
                .commentContent(comment.getContent())
                .commentImages(safeImageUrls)
                .mentionedBot(true)
                .botTriggerKind(botTriggerKind)
                .parentId(comment.getParentId())
                .replyCommentId(comment.getReplyCommentId())
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .aggregateType("COMMENT")
                .aggregateId(comment.getCommentId())
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        // 插入失败抛异常，与评论审核副作用同一事务回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new CommentFailedException("创建 bot 触发事件失败");
        }

        return eventId;
    }
```

（若 `serializePayload` 对 null 字段输出 `"answerId":null`——安全：QuantaBot `TriggerEvent` 的 `answer_id: int | None` 接受 null。）

- [ ] **Step 9: 跑测试确认通过 + 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test -Dtest="OutboxEventServiceImplBotMentionTest"` → PASS；
Run: `mvn -f demo0/pom.xml test` → 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/enums/OutboxEventType.java src/main/java/com/quanta/demo0/mq/message/BotMentionMessage.java src/main/java/com/quanta/demo0/service/OutboxEventService.java src/main/java/com/quanta/demo0/service/Impl/OutboxEventServiceImpl.java src/test/java/com/quanta/demo0/mq/message/BotMentionMessageSerializationTest.java src/test/java/com/quanta/demo0/service/Impl/OutboxEventServiceImplBotMentionTest.java
git -C demo0 commit -m "feat(bot): BOT_MENTION_REQUESTED 事件类型与 BotMentionMessage 契约消息 + Outbox 创建（D4/C-1）"
```

---

### Task 6: BotMentionDetector + 审核通过双挂载

**Files:**
- Create: `demo0/src/main/java/com/quanta/demo0/service/bot/BotMentionDetector.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/service/Impl/CommentAuditServiceImpl.java`（两处挂载）
- Test: `demo0/src/test/java/com/quanta/demo0/service/bot/BotMentionDetectorTest.java`
- Test: `demo0/src/test/java/com/quanta/demo0/service/Impl/CommentAuditServiceImplBotMentionTest.java`

**Interfaces:**
- Consumes: Task 5 的 `createBotMentionEvent`；Task 1 的 `QuantabotProperties`；`CommentMapper.selectImagesByCommentId(commentId)`。
- Produces: `BotMentionDetector.isBotMentioned(String content, String botNickname, Long replyUserId, Long botUserId) -> boolean` 与 `BotMentionDetector.triggerKind(Long replyUserId, Long botUserId) -> String`（静态方法）。挂载语义：`approveComment` 与 `approveRejectedComment` 审核通过副作用链末尾各追加一次判定，命中即发事件。

**判定规则（C-4 契约：`mentionedBot = (前端标记) || (replyUserId == botUserId)`；前端标记的物质形态=评论内容含 `@框框` 文本——@ 卡片就是往输入框插入该文本）：**
- `replyUserId == botUserId` → 命中，kind=`replied`（优先）
- 内容含 `"@" + botNickname`（大小写不敏感） → 命中，kind=`mentioned`
- 其余不命中，不发事件（bot 不消费全量评论）

- [x] **Step 1: 写失败测试（detector）**

```java
package com.quanta.demo0.service.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C-4 mention 判定：replyUserId==bot 优先（replied），
 * 文本 @昵称 兜底（mentioned——@ 卡片插入的文本形态）。
 */
class BotMentionDetectorTest {

    @Test
    void 回复bot评论_命中replied() {
        assertTrue(BotMentionDetector.isBotMentioned(
                "学长说得好", "框框", 10000L, 10000L));
        assertEquals("replied", BotMentionDetector.triggerKind(10000L, 10000L));
    }

    @Test
    void 文本含at昵称_命中mentioned() {
        assertTrue(BotMentionDetector.isBotMentioned(
                "@框框 帮我看看", "框框", null, 10000L));
        assertEquals("mentioned", BotMentionDetector.triggerKind(null, 10000L));
    }

    @Test
    void at昵称大小写不敏感_系统名形态也兜底() {
        // 系统名 QuantaBot 也认（改名过渡期人工手打兜底）
        assertTrue(BotMentionDetector.isBotMentioned(
                "@QuantaBot 在吗", "框框", null, 10000L));
    }

    @Test
    void 无mention不命中() {
        assertFalse(BotMentionDetector.isBotMentioned(
                "普通评论", "框框", null, 10000L));
        assertFalse(BotMentionDetector.isBotMentioned(
                "普通回复", "框框", 3L, 10000L));
    }

    @Test
    void 昵称是前缀的假命中_至少要求at加全名() {
        // @框 不算命中框框
        assertFalse(BotMentionDetector.isBotMentioned(
                "@框 你好", "框框", null, 10000L));
    }

    @Test
    void 回复与文本同时命中_replied优先() {
        assertEquals("replied",
                BotMentionDetector.triggerKind(10000L, 10000L));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="BotMentionDetectorTest"`
Expected: 编译失败——类不存在。

- [x] **Step 3: 实现 BotMentionDetector**

```java
package com.quanta.demo0.service.bot;

/**
 * bot mention 判定（C-4 契约）。
 *
 * demo0 在评论审核通过时判定是否命中 bot：
 *   - replyUserId == botUserId：直接回复 bot 评论，kind = replied（优先）；
 *   - 评论内容含 "@昵称" 文本（@ 卡片插入的形态，含系统名 QuantaBot 过渡兜底）：
 *     kind = mentioned。
 *
 * 前端 mentionBot DTO 标记仅作观测（不持久化）；事件侧以本判定为准。
 */
public final class BotMentionDetector {

    private BotMentionDetector() {
    }

    /**
     * 判定评论是否命中 bot。
     *
     * @param content     评论全文
     * @param botNickname bot 昵称（如"框框"）
     * @param replyUserId 被回复用户 ID（可空）
     * @param botUserId   bot 系统账号 user_id
     */
    public static boolean isBotMentioned(
            String content,
            String botNickname,
            Long replyUserId,
            Long botUserId
    ) {
        if (replyUserId != null && replyUserId.equals(botUserId)) {
            return true;
        }
        if (content == null || botNickname == null || botNickname.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return lower.contains("@" + botNickname.toLowerCase())
                // 系统名过渡兜底（改名期人工手打形态）
                || lower.contains("@quantabot");
    }

    /**
     * 触发类型：回复 bot 优先 replied，否则 mentioned。
     */
    public static String triggerKind(Long replyUserId, Long botUserId) {
        if (replyUserId != null && replyUserId.equals(botUserId)) {
            return "replied";
        }
        return "mentioned";
    }
}
```

- [x] **Step 4: 跑 detector 测试确认通过**

Run: `mvn -f demo0/pom.xml test -Dtest="BotMentionDetectorTest"`
Expected: PASS（6 个用例）。

- [x] **Step 5: 写失败测试（审核挂载）**

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.service.OutboxEventService;
import org.junit.jupiter.api.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 审核通过钩子挂载（C-1）：PENDING->APPROVED 与 REJECTED->APPROVED
 * 两条路径都判定 mention 并发 Outbox 事件；未命中不发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentAuditServiceImplBotMentionTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ContentMapper contentMapper;
    @Mock
    private QuestionMapper questionMapper;

    @InjectMocks
    private CommentAuditServiceImpl service;

    private final QuantabotProperties quantabotProperties = new QuantabotProperties();

    @Before
    void setUp() {
        ReflectionTestUtils.setField(
                service, "quantabotProperties", quantabotProperties);
    }

    private ContentComment comment(String content, Long replyUserId) {
        return ContentComment.builder()
                .commentId(100L)
                .contentId(10L)
                .parentId(null)
                .replyCommentId(null)
                .replyUserId(replyUserId)
                .userId(3L)
                .content(content)
                .auditStatus(AuditStatus.PENDING.getCode())
                .build();
    }

    private void stubApprovePath(ContentComment comment) {
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), anyInt(), eq(AuditStatus.APPROVED.getCode()),
                isNull(), isNull()))
                .thenReturn(1);
        when(commentMapper.updateCommentCount(eq(10L), anyInt())).thenReturn(1);
        when(commentMapper.selectImagesByCommentId(100L)).thenReturn(List.of());
    }

    @Test
    void 文本命中_审核通过后发事件() {
        stubApprovePath(comment("@框框 这个问题怎么解决", null));

        service.approveComment(100L, null);

        verify(outboxEventService).createBotMentionEvent(
                any(ContentComment.class), eq(List.of()), eq("mentioned"));
    }

    @Test
    void 回复bot_审核通过后发replied事件() {
        stubApprovePath(comment("学长说得对", 10000L));

        service.approveComment(100L, null);

        verify(outboxEventService).createBotMentionEvent(
                any(ContentComment.class), eq(List.of()), eq("replied"));
    }

    @Test
    void 未命中_不发事件() {
        stubApprovePath(comment("普通评论", null));

        service.approveComment(100L, null);

        verify(outboxEventService, never()).createBotMentionEvent(
                any(), any(), any());
    }

    @Test
    void 驳回转通过路径同样挂载() {
        stubApprovePath(comment("@框框 补充一下", null));
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.REJECTED.getCode()),
                eq(AuditStatus.APPROVED.getCode()), isNull(), isNull()))
                .thenReturn(1);

        service.approveRejectedComment(100L, null);

        verify(outboxEventService).createBotMentionEvent(
                any(ContentComment.class), eq(List.of()), eq("mentioned"));
    }
}
```

`updateAuditStatusIfCurrent` 按现有五参签名（commentId / oldAuditStatus / newAuditStatus / rejectReason / auditUserId）打桩。`contentMapper` 和 `questionMapper` 必须是 Mockito mock，不能为 null；本组用例的 `answerId=null`，`contentMapper.selectById` 默认返回 null 会让通知支路安全返回，不影响 bot mention 断言。

- [x] **Step 6: 跑挂载测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="CommentAuditServiceImplBotMentionTest"`
Expected: FAIL——三个 verify 用例都收不到 `createBotMentionEvent` 调用。

- [x] **Step 7: CommentAuditServiceImpl 挂载**

类内注入（与现有 `outboxEventService` 注入并列）：

```java
    @Autowired
    private QuantabotProperties quantabotProperties;
```

`approveComment(Long commentId, Long auditUserId)` 在 `createCommentSearchEvents(comment, "COMMENT_ADD");` 之后、`return true;` 之前追加：

```java
        // C-1：审核通过后判定 bot mention，命中即发 Outbox 事件（QuantaBot 触发链路）
        maybeCreateBotMentionEvent(comment);
```

`approveRejectedComment` 同位置追加同一行。类内新增私有方法：

```java
    /**
     * C-1/C-4：评论可见后判定是否命中 bot（@ 文本或直接回复），
     * 命中则与本次审核副作用同一事务写 BOT_MENTION_REQUESTED Outbox 事件。
     */
    private void maybeCreateBotMentionEvent(ContentComment comment) {
        boolean mentioned = BotMentionDetector.isBotMentioned(
                comment.getContent(),
                quantabotProperties.getBotNickname(),
                comment.getReplyUserId(),
                quantabotProperties.getBotUserId()
        );
        if (!mentioned) {
            return;
        }
        List<String> imageUrls =
                commentMapper.selectImagesByCommentId(comment.getCommentId());
        String triggerKind = BotMentionDetector.triggerKind(
                comment.getReplyUserId(), quantabotProperties.getBotUserId());
        outboxEventService.createBotMentionEvent(comment, imageUrls, triggerKind);
    }
```

并补 import：`com.quanta.demo0.properties.QuantabotProperties`、`com.quanta.demo0.service.bot.BotMentionDetector`、`java.util.List`（如缺）。

- [ ] **Step 8: 跑挂载测试确认通过 + 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test -Dtest="CommentAuditServiceImplBotMentionTest"` → PASS；
Run: `mvn -f demo0/pom.xml test` → 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/service/bot/BotMentionDetector.java src/main/java/com/quanta/demo0/service/Impl/CommentAuditServiceImpl.java src/test/java/com/quanta/demo0/service/bot/BotMentionDetectorTest.java src/test/java/com/quanta/demo0/service/Impl/CommentAuditServiceImplBotMentionTest.java
git -C demo0 commit -m "feat(bot): 审核通过双钩子挂载 bot mention 判定与事件创建（D4/C-1/C-4）"
```

---

### Task 7: quantabot MQ 拓扑 + Outbox 路由分支

**Files:**
- Modify: `demo0/src/main/java/com/quanta/demo0/config/RabbitMQConfig.java`（常量 + 队列/交换机/绑定 Bean）
- Modify: `demo0/src/main/java/com/quanta/demo0/mq/outbox/OutboxRouteRegistry.java`（resolve 加分支）
- Test: `demo0/src/test/java/com/quanta/demo0/mq/outbox/OutboxRouteRegistryBotMentionTest.java`

**Interfaces:**
- Consumes: Task 5 的 `BOT_MENTION_REQUESTED` + `BotMentionMessage`。
- Produces: 拓扑（C-1 契约命名）——交换机 `quantabot.exchange`、主队列 `quantabot.comment.queue`（QuantaBot consumer 被动消费的名字，**逐字一致**）、rk `quantabot.comment.created`、retry `quantabot.comment.retry.queue/exchange/rk`（TTL 60s 回主队列）、dlx `quantabot.comment.dlx.queue/exchange/rk`。QuantaBot 断线 5 秒重连等待拓扑（consumer.py `_RECONNECT_SECONDS`），demo0 启动即声明，无启动顺序问题。

- [x] **Step 1: 写失败测试（路由分支）**

```java
package com.quanta.demo0.mq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.enums.OutboxEventType;
import com.quanta.demo0.mq.message.BotMentionMessage;
import com.quanta.demo0.mq.message.OutboxRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BOT_MENTION_REQUESTED 路由到 quantabot 拓扑（C-1 命名逐字对齐）。
 */
class OutboxRouteRegistryBotMentionTest {

    private final OutboxRouteRegistry registry =
            new OutboxRouteRegistry(new ObjectMapper());

    private OutboxEvent event(String payload) {
        return OutboxEvent.builder()
                .eventId("evt-1")
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .aggregateType("COMMENT")
                .aggregateId(100L)
                .payload(payload)
                .build();
    }

    private String payload() {
        return """
                {
                  "eventId": "evt-1",
                  "eventType": "BOT_MENTION_REQUESTED",
                  "retryCount": 0,
                  "commentId": 100,
                  "postId": 10,
                  "commenterUserId": 3,
                  "commentContent": "@框框 你好",
                  "commentImages": [],
                  "mentionedBot": true,
                  "botTriggerKind": "mentioned"
                }
                """;
    }

    @Test
    void botMention事件路由到quantabot拓扑() {
        OutboxRoute route = registry.resolve(event(payload()));

        assertNotNull(route);
        assertEquals(RabbitMQConfig.BOT_MENTION_EXCHANGE, route.getExchange());
        assertEquals(RabbitMQConfig.BOT_MENTION_ROUTING_KEY, route.getRoutingKey());
        assertInstanceOf(BotMentionMessage.class, route.getMessage());
        assertEquals(100L, ((BotMentionMessage) route.getMessage()).getCommentId());
    }
}
```

`OutboxRoute` 实体在 `mq/message/OutboxRoute.java`，Lombok `@Data` 产生 `getExchange/getRoutingKey/getMessage`；`OutboxRouteRegistry` 现有唯一构造依赖是 `ObjectMapper`，测试按上述显式构造。

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="OutboxRouteRegistryBotMentionTest"`
Expected: FAIL——`resolve` 对 BOT_MENTION_REQUESTED 走到末尾抛 IllegalArgumentException（未知类型）或编译失败（常量不存在）。

- [x] **Step 3: RabbitMQConfig 加 quantabot 拓扑**

常量区（`SEARCH_RECONCILE_*` 之后）追加：

```java
    // ========== QuantaBot 触发链路（C-1 契约）==========
    // 交换机/队列/路由键名与 Phase0-契约谈判.md C-1 及 QuantaBot consumer.py
    // 的 QUANTABOT_QUEUE 逐字一致（bot 侧被动消费，不声明拓扑——demo0 负责声明）
    public static final String BOT_MENTION_EXCHANGE = "quantabot.exchange";
    public static final String BOT_MENTION_QUEUE = "quantabot.comment.queue";
    public static final String BOT_MENTION_ROUTING_KEY = "quantabot.comment.created";

    public static final String BOT_MENTION_RETRY_QUEUE = "quantabot.comment.retry.queue";
    public static final String BOT_MENTION_RETRY_EXCHANGE = "quantabot.comment.retry.exchange";
    public static final String BOT_MENTION_RETRY_ROUTING_KEY = "quantabot.comment.retry";

    public static final String BOT_MENTION_DLX_QUEUE = "quantabot.comment.dlx.queue";
    public static final String BOT_MENTION_DLX_EXCHANGE = "quantabot.comment.dlx.exchange";
    public static final String BOT_MENTION_DLX_ROUTING_KEY = "quantabot.comment.dlx";
```

Bean 区追加（照 moderation 三件套模式）：

```java
    // ========== QuantaBot 触发链路拓扑（C-1）==========

    /**
     * bot 触发主队列（QuantaBot 被动消费；消费失败 DLX 进死信）。
     */
    @Bean
    public Queue botMentionQueue() {
        return QueueBuilder.durable(BOT_MENTION_QUEUE)
                .withArgument("x-dead-letter-exchange", BOT_MENTION_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", BOT_MENTION_DLX_ROUTING_KEY)
                .build();
    }

    /**
     * bot 触发重试队列：停留 60 秒后经 DLX 回主队列。
     */
    @Bean
    public Queue botMentionRetryQueue() {
        return QueueBuilder.durable(BOT_MENTION_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", BOT_MENTION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", BOT_MENTION_ROUTING_KEY)
                .build();
    }

    /**
     * bot 触发最终死信队列。
     */
    @Bean
    public Queue botMentionDlxQueue() {
        return QueueBuilder.durable(BOT_MENTION_DLX_QUEUE).build();
    }

    @Bean
    public DirectExchange botMentionExchange() {
        return new DirectExchange(BOT_MENTION_EXCHANGE);
    }

    @Bean
    public DirectExchange botMentionRetryExchange() {
        return new DirectExchange(BOT_MENTION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange botMentionDlxExchange() {
        return new DirectExchange(BOT_MENTION_DLX_EXCHANGE);
    }

    @Bean
    public Binding botMentionBinding() {
        return BindingBuilder.bind(botMentionQueue())
                .to(botMentionExchange())
                .with(BOT_MENTION_ROUTING_KEY);
    }

    @Bean
    public Binding botMentionRetryBinding() {
        return BindingBuilder.bind(botMentionRetryQueue())
                .to(botMentionRetryExchange())
                .with(BOT_MENTION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding botMentionDlxBinding() {
        return BindingBuilder.bind(botMentionDlxQueue())
                .to(botMentionDlxExchange())
                .with(BOT_MENTION_DLX_ROUTING_KEY);
    }
```

- [x] **Step 4: OutboxRouteRegistry 加分支**

`resolve()` 内（现有分支之后、未知类型抛异常之前）追加：

```java
        /**
         * bot 触发事件（C-1）：路由到 quantabot 拓扑。
         */
        if (OutboxEventType.BOT_MENTION_REQUESTED.getCode()
                .equals(event.getEventType())) {
            BotMentionMessage message =
                    deserializePayload(event.getPayload(), BotMentionMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.BOT_MENTION_EXCHANGE)
                    .routingKey(RabbitMQConfig.BOT_MENTION_ROUTING_KEY)
                    .message(message)
                    .build();
        }
```

并补 import `com.quanta.demo0.mq.message.BotMentionMessage`（其余 import 已有）。

- [ ] **Step 5: 跑测试确认通过 + 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test -Dtest="OutboxRouteRegistryBotMentionTest"` → PASS；
Run: `mvn -f demo0/pom.xml test` → 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/config/RabbitMQConfig.java src/main/java/com/quanta/demo0/mq/outbox/OutboxRouteRegistry.java src/test/java/com/quanta/demo0/mq/outbox/OutboxRouteRegistryBotMentionTest.java
git -C demo0 commit -m "feat(bot): quantabot MQ 拓扑声明与 BOT_MENTION_REQUESTED Outbox 路由（D4/C-1）"
```

---

## 批3：读取与同步（Task 8-10）

> demo0 侧三只读接口（D5）+ 内容同步与政策通道（D6 双侧）。前置：批1+批2 全绿合入（bot 账号/service token/触发链路就位——bot 才有东西可读）。

### Task 8：D5 bot 只读三接口（chain / history / tree）——C-2①②③

**Files**：
- Modify：`demo0/src/main/java/com/quanta/demo0/mapper/CommentMapper.java`（追加 6 个只读方法）
- Modify：`demo0/src/main/resources/mapper/CommentMapper.xml`（追加批量图片查询）
- Create：`demo0/src/main/java/com/quanta/demo0/controller/bot/BotCommentController.java`
- Create：`demo0/src/main/java/com/quanta/demo0/controller/bot/vo/BotPostVO.java`、`BotCommentNodeVO.java`、`BotCommentChainVO.java`、`BotCommentHistoryVO.java`、`BotCommentTreeVO.java`
- Create：`demo0/src/main/java/com/quanta/demo0/service/BotCommentService.java`、`demo0/src/main/java/com/quanta/demo0/service/Impl/BotCommentServiceImpl.java`
- Create：`demo0/src/test/java/com/quanta/demo0/service/BotCommentServiceImplTest.java`

**Interfaces**（对端=QuantaBot `HTTPCommentTreeFetcher`，字段 alias 见 `pipeline/ports.py` PostSummary/CommentNode + `infra/main_service.py` 三个 Response DTO）：

| 接口 | 参数 | 响应 data 形状 | QuantaBot 消费方 |
|---|---|---|---|
| `GET /bot/comment/chain` | `commentId` | `{post: {postId,userId,title,content}, chain: [节点…]}` | `fetch_context`（C-2①） |
| `GET /bot/comment/history` | `userId, postId, pageNum=1, pageSize=50` | `{list: [节点…], total}` | `fetch_context`（C-2③ bot 本帖历史） |
| `GET /bot/comment/tree` | `postId, pageNum=1, pageSize=50, sortType=asc` | `{total, list: [节点…]}` | `fetch_floors`（C-2② 全量楼层分页拉满） |

节点字段（与 CommentNode alias 逐字一致）：`commentId/parentId/replyCommentId/userId/content/images/createTime`。
**契约硬约束**：
1. `images` 永不为 null（空数组 `[]`——Pydantic `tuple[str,...]` 收 null 会校验失败变毒丸）；
2. 顶级评论 `parentId` 为 0 的历史数据必须归一化为 `null`（bot 端 `parent_id is not None` 判一级，0 会污染分区逻辑）；
3. 可见性过滤：`audit_status = 1 AND is_deleted = 0`（bot 只读可见评论——待审/驳回/已删一律不出现）；
4. `chain` 顺序=时间正序（一级在前、触发评论在最后）——bot 端 `build_channel_b` 对 chain 直接 join 渲染；
5. `tree` 含楼中楼全部评论（bot 端 `partition_floors` 自行近远分区）。

**步骤**：

- [x] **Step 1: 写失败测试 `BotCommentServiceImplTest`**

```java
package com.quanta.demo0.service;

import com.quanta.demo0.controller.bot.vo.BotCommentChainVO;
import com.quanta.demo0.controller.bot.vo.BotCommentHistoryVO;
import com.quanta.demo0.controller.bot.vo.BotCommentTreeVO;
import com.quanta.demo0.entity.CommentImage;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.exception.CommentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.Impl.BotCommentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * bot 只读三接口单元测试（C-2）：链遍历/parentId 归一/images 永非 null/可见性过滤/分页。
 */
@ExtendWith(MockitoExtension.class)
class BotCommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private ContentMapper contentMapper;

    @InjectMocks
    private BotCommentServiceImpl botCommentService;

    private static ContentComment comment(Long id, Long parentId, Long userId, String content) {
        return ContentComment.builder()
                .commentId(id)
                .contentId(9001L)
                .parentId(parentId)
                .replyCommentId(null)
                .replyUserId(null)
                .userId(userId)
                .content(content)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(LocalDateTime.of(2026, 9, 18, 10, 0, 0).plusMinutes(id))
                .build();
    }

    @Test
    void chain_returns_ancestor_chain_in_time_order_with_normalized_parent_id() {
        // 三层链：一级(9101, parentId=0 历史脏数据) → 楼内回复(9102) → 触发评论(9103)
        ContentComment trigger = comment(9103L, 9101L, 42L, "@框框 选课求指点");
        trigger.setReplyCommentId(9102L);
        ContentComment middle = comment(9102L, 9101L, 7L, "同问");
        ContentComment top = comment(9101L, 0L, 7L, "求助选课");
        when(commentMapper.selectVisibleById(9103L)).thenReturn(trigger);
        lenient().when(commentMapper.selectVisibleById(9102L)).thenReturn(middle);
        when(commentMapper.selectVisibleById(9101L)).thenReturn(top);
        when(contentMapper.selectById(9001L)).thenReturn(Content.builder()
                .contentId(9001L).contentType(1).title("选课帖").content("主楼内容")
                .publishUserId(7L).auditStatus(1).isDeleted(0).build());
        // 触发评论带图，其余无图
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of(
                CommentImage.builder().commentId(9103L).imageUrl("https://img/1.png").build()
        ));

        BotCommentChainVO vo = botCommentService.getChain(9103L);

        assertThat(vo.getPost().getPostId()).isEqualTo(9001L);
        assertThat(vo.getPost().getUserId()).isEqualTo(7L);
        assertThat(vo.getPost().getTitle()).isEqualTo("选课帖");
        // 链时间正序：一级在前、触发评论在最后
        assertThat(vo.getChain()).extracting("commentId")
                .containsExactly(9101L, 9102L, 9103L);
        // parentId=0 归一 null；触发评论带图；无图节点 images 为空数组而非 null
        assertThat(vo.getChain().get(0).getParentId()).isNull();
        assertThat(vo.getChain().get(2).getImages()).containsExactly("https://img/1.png");
        assertThat(vo.getChain().get(0).getImages()).isNotNull().isEmpty();
    }

    @Test
    void chain_throws_when_trigger_comment_not_visible() {
        when(commentMapper.selectVisibleById(999L)).thenReturn(null);

        assertThatThrownBy(() -> botCommentService.getChain(999L))
                .isInstanceOf(CommentFailedException.class)
                .hasMessageContaining("评论不存在");
    }

    @Test
    void history_returns_page_with_total_and_non_null_list() {
        when(commentMapper.countBotHistory(10000L, 9001L)).thenReturn(3L);
        when(commentMapper.selectBotHistory(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(comment(9101L, null, 10000L, "bot 上一条")));
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of());

        BotCommentHistoryVO vo = botCommentService.getHistory(10000L, 9001L, 1, 50);

        assertThat(vo.getTotal()).isEqualTo(3L);
        assertThat(vo.getList()).hasSize(1);
        assertThat(vo.getList().get(0).getUserId()).isEqualTo(10000L);
        assertThat(vo.getList().get(0).getImages()).isNotNull().isEmpty();
    }

    @Test
    void tree_returns_floors_sorted_asc_including_nested_replies() {
        when(commentMapper.countBotFloors(9001L)).thenReturn(2L);
        when(commentMapper.selectBotFloorsAsc(anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        comment(9101L, null, 7L, "一级"),
                        comment(9102L, 9101L, 8L, "楼中楼")));
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of());

        BotCommentTreeVO vo = botCommentService.getTree(9001L, 1, 50, "asc");

        assertThat(vo.getTotal()).isEqualTo(2L);
        // 含楼中楼（bot 端 partition_floors 自行分区）
        assertThat(vo.getList()).extracting("commentId").containsExactly(9101L, 9102L);
        assertThat(vo.getList().get(1).getParentId()).isEqualTo(9101L);
    }

    @Test
    void tree_sort_type_desc_uses_desc_query_and_unknown_sort_normalizes_to_asc() {
        when(commentMapper.countBotFloors(9001L)).thenReturn(0L);
        when(commentMapper.selectBotFloorsDesc(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of());

        assertThat(botCommentService.getTree(9001L, 1, 50, "desc").getList()).isEmpty();

        // 非 desc 一律归一 asc（bot 端只传 asc；白名单防御 SQL 拼接）
        when(commentMapper.selectBotFloorsAsc(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        assertThat(botCommentService.getTree(9001L, 1, 50, "weird").getList()).isEmpty();
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="BotCommentServiceImplTest"`
Expected: COMPILATION ERROR——`controller.bot.vo.*`、`BotCommentServiceImpl` 不存在。

- [x] **Step 3: 建 5 个 VO**

`demo0/src/main/java/com/quanta/demo0/controller/bot/vo/BotPostVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * bot 视角的帖子主楼摘要（C-2①）。
 * 字段名与 QuantaBot PostSummary alias 逐字一致（postId/userId/title/content）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotPostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 帖子 ID（= tb_content.content_id，C-1 postId 口径） */
    private Long postId;

    /** 发帖用户 ID（= tb_content.publish_user_id） */
    private Long userId;

    private String title;

    private String content;
}
```

`BotCommentNodeVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * bot 视角的评论节点（C-2①；chain/history/tree 三接口共用）。
 * 字段名与 QuantaBot CommentNode alias 逐字一致。
 * 契约硬约束：images 永不为 null（空数组）；顶级评论 parentId 必须为 null（0 归一化）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long commentId;

    /** 一级评论为 null（bot 端以 parent_id is None 判一级） */
    private Long parentId;

    private Long replyCommentId;

    private Long userId;

    private String content;

    /** 永不为 null——空数组兜底（Pydantic tuple 收 null 会校验失败） */
    private List<String> images;

    /** yyyy-MM-dd HH:mm:ss */
    private String createTime;
}
```

`BotCommentChainVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * chain 接口响应（C-2①）：主楼摘要 + 触发评论祖先链（时间正序，触发评论在最后）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentChainVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BotPostVO post;

    private List<BotCommentNodeVO> chain;
}
```

`BotCommentHistoryVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * history 接口响应（C-2③）：用户在某帖下的可见评论分页。
 * 字段名 list/total 与 QuantaBot CommentHistoryResponse 对齐。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BotCommentNodeVO> list;

    private long total;
}
```

`BotCommentTreeVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * tree 接口响应（C-2②）：帖子全量可见楼层（含楼中楼）分页。
 * 字段名 total/list 与 QuantaBot CommentTreePage 对齐。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentTreeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;

    private List<BotCommentNodeVO> list;
}
```

- [x] **Step 4: CommentMapper 追加 6 个只读方法 + XML 批量图片查询**

`CommentMapper.java` 末尾（`selectImagesByCommentId` 之后）追加：

```java
    // ========== QuantaBot 只读三接口（C-2；仅审核通过且未删的评论可见）==========

    /**
     * bot 链接口：按 ID 查可见评论（audit_status=1 且未删）。
     */
    @Select("select * from tb_content_comment where comment_id = #{commentId} and audit_status = 1 and is_deleted = 0")
    ContentComment selectVisibleById(Long commentId);

    /**
     * bot 历史接口：用户在某帖下的可见评论（时间正序，SQL 分页）。
     */
    @Select("select * from tb_content_comment where user_id = #{userId} and content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time asc, comment_id asc limit #{offset}, #{limit}")
    List<ContentComment> selectBotHistory(@Param("userId") Long userId, @Param("postId") Long postId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * bot 历史接口：总数。
     */
    @Select("select count(*) from tb_content_comment where user_id = #{userId} and content_id = #{postId} and audit_status = 1 and is_deleted = 0")
    long countBotHistory(@Param("userId") Long userId, @Param("postId") Long postId);

    /**
     * bot 树接口：帖子全部可见评论（含楼中楼）——asc（bot 端 fetch_floors 唯一调用形态）。
     */
    @Select("select * from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time asc, comment_id asc limit #{offset}, #{limit}")
    List<ContentComment> selectBotFloorsAsc(@Param("postId") Long postId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * bot 树接口：desc 档（白名单双方法——禁止 SQL 拼接排序方向）。
     */
    @Select("select * from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time desc, comment_id desc limit #{offset}, #{limit}")
    List<ContentComment> selectBotFloorsDesc(@Param("postId") Long postId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * bot 树接口：总数。
     */
    @Select("select count(*) from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0")
    long countBotFloors(@Param("postId") Long postId);

    /**
     * 批量查询评论图片（bot 三接口组装 VO 用——避免 N+1）。
     */
    List<com.quanta.demo0.entity.CommentImage> selectImagesByCommentIds(@Param("commentIds") List<Long> commentIds);
```

`CommentMapper.xml` 末尾（`</mapper>` 前）追加：

```xml
    <select id="selectImagesByCommentIds" resultType="com.quanta.demo0.entity.CommentImage">
        SELECT comment_id, image_url FROM tb_comment_image
        WHERE comment_id IN
        <foreach collection="commentIds" item="cid" open="(" separator="," close=")">
            #{cid}
        </foreach>
    </select>
```

- [x] **Step 5: 建 Service 接口与实现**

`demo0/src/main/java/com/quanta/demo0/service/BotCommentService.java`：

```java
package com.quanta.demo0.service;

import com.quanta.demo0.controller.bot.vo.BotCommentChainVO;
import com.quanta.demo0.controller.bot.vo.BotCommentHistoryVO;
import com.quanta.demo0.controller.bot.vo.BotCommentTreeVO;

/**
 * bot 只读三接口（C-2：chain 父链 / history 本帖历史 / tree 全量楼层）。
 * 仅供 bot 系统账号（BOT 角色）调用——与面向用户的评论读接口隔离。
 */
public interface BotCommentService {

    /** C-2①：触发评论 + 祖先链（时间正序）+ 主楼摘要。 */
    BotCommentChainVO getChain(Long commentId);

    /** C-2③：用户在某帖下的可见评论分页。 */
    BotCommentHistoryVO getHistory(Long userId, Long postId, int pageNum, int pageSize);

    /** C-2②：帖子全量可见楼层（含楼中楼）分页；sortType 仅 asc/desc，其余归一 asc。 */
    BotCommentTreeVO getTree(Long postId, int pageNum, int pageSize, String sortType);
}
```

`demo0/src/main/java/com/quanta/demo0/service/Impl/BotCommentServiceImpl.java`：

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.controller.bot.vo.BotCommentChainVO;
import com.quanta.demo0.controller.bot.vo.BotCommentHistoryVO;
import com.quanta.demo0.controller.bot.vo.BotCommentNodeVO;
import com.quanta.demo0.controller.bot.vo.BotCommentTreeVO;
import com.quanta.demo0.controller.bot.vo.BotPostVO;
import com.quanta.demo0.entity.CommentImage;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.exception.CommentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.BotCommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * bot 只读三接口实现（C-2）。
 *
 * 契约硬约束（对端 Pydantic 校验闸门）：
 * ① images 永不为 null（空数组兜底）；
 * ② 顶级评论 parentId=0 的历史数据归一化为 null；
 * ③ 可见性过滤 audit_status=1 AND is_deleted=0；
 * ④ chain 时间正序（一级在前、触发评论在最后）。
 */
@Service
@Slf4j
public class BotCommentServiceImpl implements BotCommentService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 祖先链深度上限（防御脏数据环——parentId 指向后代时避免死循环）。 */
    private static final int MAX_CHAIN_DEPTH = 50;

    private static final int MAX_PAGE_SIZE = 200;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private ContentMapper contentMapper;

    @Override
    public BotCommentChainVO getChain(Long commentId) {
        ContentComment trigger = commentMapper.selectVisibleById(commentId);
        if (trigger == null) {
            throw new CommentFailedException("评论不存在或不可见");
        }
        Content post = contentMapper.selectById(trigger.getContentId());
        if (post == null) {
            throw new CommentFailedException("帖子不存在或已删除");
        }

        // 沿直接回复对象向上遍历：楼中楼优先 replyCommentId，回复一级时回退 parentId
        Deque<ContentComment> stack = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        ContentComment cur = trigger;
        while (cur != null && visited.add(cur.getCommentId()) && stack.size() < MAX_CHAIN_DEPTH) {
            stack.push(cur);
            Long ancestorId = cur.getReplyCommentId() != null
                    ? cur.getReplyCommentId()
                    : cur.getParentId();
            cur = (ancestorId == null || ancestorId == 0L)
                    ? null
                    : commentMapper.selectVisibleById(ancestorId);
        }

        // 反转：一级在前、触发评论在最后（时间正序）
        List<ContentComment> ordered = new ArrayList<>(stack);
        List<BotCommentNodeVO> chain = toNodes(ordered);

        return BotCommentChainVO.builder()
                .post(BotPostVO.builder()
                        .postId(post.getContentId())
                        .userId(post.getPublishUserId())
                        .title(post.getTitle())
                        .content(post.getContent())
                        .build())
                .chain(chain)
                .build();
    }

    @Override
    public BotCommentHistoryVO getHistory(Long userId, Long postId, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = commentMapper.countBotHistory(userId, postId);
        List<ContentComment> rows = commentMapper.selectBotHistory(
                userId, postId, (page - 1) * size, size);
        return BotCommentHistoryVO.builder()
                .list(toNodes(rows))
                .total(total)
                .build();
    }

    @Override
    public BotCommentTreeVO getTree(Long postId, int pageNum, int pageSize, String sortType) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = commentMapper.countBotFloors(postId);
        List<ContentComment> rows = "desc".equals(sortType)
                ? commentMapper.selectBotFloorsDesc(postId, (page - 1) * size, size)
                : commentMapper.selectBotFloorsAsc(postId, (page - 1) * size, size);
        return BotCommentTreeVO.builder()
                .total(total)
                .list(toNodes(rows))
                .build();
    }

    // ---------- 组装 ----------

    private List<BotCommentNodeVO> toNodes(List<ContentComment> rows) {
        Map<Long, List<String>> imagesByCommentId = loadImages(rows);
        return rows.stream()
                .map(c -> BotCommentNodeVO.builder()
                        .commentId(c.getCommentId())
                        .parentId(normalizeParentId(c.getParentId()))
                        .replyCommentId(c.getReplyCommentId())
                        .userId(c.getUserId())
                        .content(c.getContent())
                        .images(imagesByCommentId.getOrDefault(c.getCommentId(), List.of()))
                        .createTime(c.getCreateTime() == null ? "" : DATE_TIME_FORMATTER.format(c.getCreateTime()))
                        .build())
                .collect(Collectors.toList());
    }

    /** 批量拉图片并按评论分组（空列表直接返回——不发 IN () 空集合 SQL）。 */
    private Map<Long, List<String>> loadImages(List<ContentComment> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = rows.stream().map(ContentComment::getCommentId).toList();
        List<CommentImage> images = commentMapper.selectImagesByCommentIds(ids);
        return images.stream().collect(Collectors.groupingBy(
                CommentImage::getCommentId,
                Collectors.mapping(CommentImage::getImageUrl, Collectors.toList())));
    }

    /** 顶级评论 parentId=0 历史数据归一 null（bot 端以 parent_id is None 判一级）。 */
    private Long normalizeParentId(Long parentId) {
        return (parentId == null || parentId == 0L) ? null : parentId;
    }
}
```

- [x] **Step 6: 建 Controller**

`demo0/src/main/java/com/quanta/demo0/controller/bot/BotCommentController.java`：

```java
package com.quanta.demo0.controller.bot;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.controller.bot.vo.BotCommentChainVO;
import com.quanta.demo0.controller.bot.vo.BotCommentHistoryVO;
import com.quanta.demo0.controller.bot.vo.BotCommentTreeVO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.BotCommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * bot 只读三接口（C-2；D5）。
 *
 * 鉴权：仅 BOT 角色的 service token 可调（Task 1/2 就位后 user_id=10000 持
 * tokenType=service 的 JWT → ROLE_BOT）。
 * 限流：宽松档 bot-read 120 次/分（一次触发事件 = chain 1 + history 1 + tree 分页 N 次）；
 * failClosed=false——只读幂等接口，Redis 抖动不打断 bot 回复链路。
 */
@RestController
@RequestMapping("/bot/comment")
@PreAuthorize("hasRole('BOT')")
@Slf4j
public class BotCommentController {

    @Autowired
    private BotCommentService botCommentService;

    /**
     * C-2①：触发评论 + 祖先链 + 主楼摘要。
     */
    @GetMapping("/chain")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotCommentChainVO> chain(@RequestParam Long commentId) {
        return Result.success(botCommentService.getChain(commentId));
    }

    /**
     * C-2③：用户在某帖下的可见评论分页（bot 本帖历史——防穿越楼层快照数据源）。
     */
    @GetMapping("/history")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotCommentHistoryVO> history(
            @RequestParam Long userId,
            @RequestParam Long postId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(botCommentService.getHistory(userId, postId, pageNum, pageSize));
    }

    /**
     * C-2②：帖子全量可见楼层分页（含楼中楼；bot 端分页拉满 total）。
     */
    @GetMapping("/tree")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotCommentTreeVO> tree(
            @RequestParam Long postId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize,
            @RequestParam(defaultValue = "asc") String sortType) {
        return Result.success(botCommentService.getTree(postId, pageNum, pageSize, sortType));
    }
}
```

- [ ] **Step 7: 跑测试确认通过 + 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test -Dtest="BotCommentServiceImplTest"` → PASS（5 用例）；
Run: `mvn -f demo0/pom.xml test` → 全绿。

```bash
git -C demo0 add src/main/java/com/quanta/demo0/mapper/CommentMapper.java src/main/resources/mapper/CommentMapper.xml src/main/java/com/quanta/demo0/controller/bot/ src/main/java/com/quanta/demo0/service/BotCommentService.java src/main/java/com/quanta/demo0/service/Impl/BotCommentServiceImpl.java src/test/java/com/quanta/demo0/service/BotCommentServiceImplTest.java
git -C demo0 commit -m "feat(bot): D5 只读三接口 chain/history/tree（C-2①②③；BOT 角色鉴权+宽松限流+强类型 VO）"
```

**执行注记**：
- 直接回复链优先沿 `replyCommentId`，其为空时才沿 `parentId` 回到所属一级楼层；父级被删/驳回时链自然截断（`selectVisibleById` 返回 null 停止向上）——返回已收集的部分链，不抛错（bot 端上下文降级优于整单失败）；
- Controller 层不做 MockMvc 单测（BOT 角色 service token 的 Security 上下文组装成本高）——真实鉴权路径由 Task 14 的 `07-bot.http` 套件端到端验证；
- `CommentFailedException` 沿用 demo0 现有异常体系（全局异常处理器转 Result 错误码），与 `CommentServiceImpl` 现有「评论不存在」抛法同口径。

### Task 9：D6 内容源同步接口 + 政策文档表与录入通道（demo0 侧）——C-3

**Files**：
- Modify：`demo0/src/main/resources/db/dev-seed-incremental.sql`（第 14 段：建 `tb_bot_policy_doc` 表）
- Create：`demo0/src/main/java/com/quanta/demo0/entity/BotPolicyDoc.java`
- Create：`demo0/src/main/java/com/quanta/demo0/mapper/BotContentSyncMapper.java` + `demo0/src/main/resources/mapper/BotContentSyncMapper.xml`
- Create：`demo0/src/main/java/com/quanta/demo0/controller/bot/vo/BotSyncDocVO.java`、`BotSyncPageVO.java`、`demo0/src/main/java/com/quanta/demo0/dto/BotPolicyDocDTO.java`
- Create：`demo0/src/main/java/com/quanta/demo0/service/BotContentSyncService.java`、`demo0/src/main/java/com/quanta/demo0/service/Impl/BotContentSyncServiceImpl.java`
- Create：`demo0/src/main/java/com/quanta/demo0/controller/bot/BotContentController.java`
- Create：`demo0/src/test/java/com/quanta/demo0/service/BotContentSyncServiceImplTest.java`

**Interfaces**（对端=QuantaBot `ContentSyncClient.fetch_sync`——`infra/content_sync.py`）：

| 接口 | 参数 | 响应 data 形状 |
|---|---|---|
| `GET /bot/content/sync` | `since, pageNum=1, pageSize=50` | `{items: [SyncDoc…], hasMore}` |
| `POST /bot/knowledge/policy-docs` | body `{docId, title, content}` | `Result<Void>`（upsert） |
| `DELETE /bot/knowledge/policy-docs/{docId}` | path docId | `Result<Void>`（软删→墓碑） |

SyncDoc 形状（与 QuantaBot `SyncDoc` alias 逐字一致 + 墓碑扩展）：`docId/docKind(POST|ANSWER|POLICY)/contentId/answerId/title/content/createTime/updateTime/updatedAt/status`。

**三源 UNION 语义**：
1. **POST** ← `tb_content`（`docId="content:{contentId}"`；audit_status=1 或 is_deleted=1）；
2. **ANSWER** ← `tb_question_answer`（`docId="answer:{answerId}"`；title=所属问题标题 LEFT JOIN——回答无标题，挂问题标题提升检索向量化质量）；
3. **POLICY** ← `tb_bot_policy_doc`（docId=运营录入的业务 ID，如 `policy-scholarship`）。

**墓碑语义（Phase0 §5 待定项「删除/编辑清理语义」的落地决议）**：
- 软删除记录以 `status="deleted"` 出现在同步流中，**docId 与原记录完全一致**（bot 端 Qdrant 点 ID 由 docId 派生——Task 10 靠它删对点）；
- 软删除均已带 `update_time=NOW()`（`ContentMapper.xml` softDeleteContent/softDeleteContentComment、`QuestionMapper.softDeleteAnswer` 已确认），增量拉取不漏墓碑；
- `since` 用 `update_time >= since`（宁重复勿遗漏——upsert/删除幂等；水位线归 M5）；
- 驳回（audit_status=2）不同步——重审通过会更新 update_time 自然回流。

**步骤**：

- [x] **Step 1: 写失败测试 `BotContentSyncServiceImplTest`**

```java
package com.quanta.demo0.service;

import com.quanta.demo0.controller.bot.vo.BotSyncDocVO;
import com.quanta.demo0.controller.bot.vo.BotSyncPageVO;
import com.quanta.demo0.dto.BotPolicyDocDTO;
import com.quanta.demo0.entity.BotPolicyDoc;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.BotContentSyncMapper;
import com.quanta.demo0.service.Impl.BotContentSyncServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容源同步服务单元测试（C-3）：since 解析/hasMore/墓碑透传/政策 upsert 分流。
 */
@ExtendWith(MockitoExtension.class)
class BotContentSyncServiceImplTest {

    @Mock
    private BotContentSyncMapper botContentSyncMapper;

    @InjectMocks
    private BotContentSyncServiceImpl botContentSyncService;

    private static BotSyncDocVO doc(String docId, String docKind, String status) {
        BotSyncDocVO vo = new BotSyncDocVO();
        vo.setDocId(docId);
        vo.setDocKind(docKind);
        vo.setContentId("content:1".equals(docId) ? 9001L : null);
        vo.setAnswerId("answer:5".equals(docId) ? 5L : null);
        vo.setTitle("标题");
        vo.setContent("正文");
        vo.setCreateTime("2026-09-18 10:00:00");
        vo.setUpdateTime("2026-09-18 10:30:00");
        vo.setUpdatedAt("2026-09-18 10:30:00");
        vo.setStatus(status);
        return vo;
    }

    @Test
    void sync_returns_items_and_has_more_false_when_page_covers_total() {
        when(botContentSyncMapper.countSync(any(LocalDateTime.class))).thenReturn(2L);
        when(botContentSyncMapper.selectSyncBatch(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of(
                        doc("content:9001", "POST", "active"),
                        doc("content:9002", "POST", "deleted")));

        BotSyncPageVO vo = botContentSyncService.getSync("1970-01-01", 1, 50);

        assertThat(vo.getItems()).hasSize(2);
        // 墓碑原样透传（docId 不变——bot 端靠它删 Qdrant 点）
        assertThat(vo.getItems().get(1).getStatus()).isEqualTo("deleted");
        assertThat(vo.getItems().get(1).getDocId()).isEqualTo("content:9002");
        assertThat(vo.isHasMore()).isFalse();
    }

    @Test
    void sync_has_more_true_when_total_exceeds_page() {
        when(botContentSyncMapper.countSync(any(LocalDateTime.class))).thenReturn(120L);
        when(botContentSyncMapper.selectSyncBatch(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertThat(botContentSyncService.getSync("1970-01-01", 1, 50).isHasMore()).isTrue();
        assertThat(botContentSyncService.getSync("1970-01-01", 3, 50).isHasMore()).isFalse();
    }

    @Test
    void sync_parses_since_in_both_formats_and_rejects_garbage() {
        when(botContentSyncMapper.countSync(any(LocalDateTime.class))).thenReturn(0L);
        when(botContentSyncMapper.selectSyncBatch(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of());

        // 日期格式 → 当日 00:00:00；日期时间格式 → 原值
        botContentSyncService.getSync("2026-09-18", 1, 50);
        botContentSyncService.getSync("2026-09-18 10:00:00", 1, 50);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(botContentSyncMapper, org.mockito.Mockito.times(2)).countSync(captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0, 0));
        assertThat(captor.getAllValues().get(1)).isEqualTo(LocalDateTime.of(2026, 9, 18, 10, 0, 0));

        assertThatThrownBy(() -> botContentSyncService.getSync("not-a-date", 1, 50))
                .isInstanceOf(ContentFailedException.class)
                .hasMessageContaining("since 格式非法");
    }

    @Test
    void upsert_policy_doc_inserts_when_doc_id_absent() {
        when(botContentSyncMapper.selectByDocId("policy-scholarship")).thenReturn(null);

        botContentSyncService.upsertPolicyDoc(new BotPolicyDocDTO(
                "policy-scholarship", "奖助学金评审流程", "每年 9 月启动……"));

        verify(botContentSyncMapper).insertPolicyDoc(any(BotPolicyDoc.class));
        verify(botContentSyncMapper, never()).updatePolicyDocByDocId(any(BotPolicyDoc.class));
    }

    @Test
    void upsert_policy_doc_updates_when_doc_id_exists() {
        when(botContentSyncMapper.selectByDocId("policy-scholarship"))
                .thenReturn(BotPolicyDoc.builder().id(1L).docId("policy-scholarship").build());

        botContentSyncService.upsertPolicyDoc(new BotPolicyDocDTO(
                "policy-scholarship", "奖助学金评审流程（修订）", "新正文"));

        verify(botContentSyncMapper).updatePolicyDocByDocId(any(BotPolicyDoc.class));
        verify(botContentSyncMapper, never()).insertPolicyDoc(any(BotPolicyDoc.class));
    }

    @Test
    void soft_delete_policy_doc_delegates_to_mapper() {
        when(botContentSyncMapper.softDeletePolicyDocByDocId("policy-x")).thenReturn(1);

        botContentSyncService.softDeletePolicyDoc("policy-x");

        verify(botContentSyncMapper).softDeletePolicyDocByDocId("policy-x");
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f demo0/pom.xml test -Dtest="BotContentSyncServiceImplTest"`
Expected: COMPILATION ERROR——`BotContentSyncMapper`/`BotContentSyncServiceImpl` 等不存在。

- [x] **Step 3: seed 第 14 段——建政策文档表**

`dev-seed-incremental.sql` 末尾追加第 14 段（沿用文件幂等惯例）：

```sql
-- ============================================================
-- 14) QuantaBot 政策文档表（C-3/D6：运营录入政策源，bot 经 /bot/content/sync 消费）
-- ============================================================

CREATE TABLE IF NOT EXISTS tb_bot_policy_doc (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    doc_id      VARCHAR(64)  NOT NULL COMMENT '业务文档 ID（sync docId 原样透传，如 policy-scholarship）',
    title       VARCHAR(200) NOT NULL,
    content     TEXT         NOT NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bot_policy_doc_id (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'QuantaBot 政策文档（RAG 内容源之一）';
```

- [x] **Step 4: 建 Entity / VO / DTO**

`demo0/src/main/java/com/quanta/demo0/entity/BotPolicyDoc.java`：

```java
package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 政策文档实体（tb_bot_policy_doc；运营录入的政策源——C-3 POLICY 类文档）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotPolicyDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 业务文档 ID（sync 流 docId 原样透传） */
    private String docId;

    private String title;

    private String content;

    /** 0-正常 1-已删（墓碑：软删后仍进 sync 流，status=deleted） */
    private Integer isDeleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
```

`demo0/src/main/java/com/quanta/demo0/controller/bot/vo/BotSyncDocVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 同步文档（C-3；字段名与 QuantaBot SyncDoc alias 逐字一致 + status 墓碑扩展）。
 * 注意：title/content 由 SQL IFNULL 兜底空串（Pydantic str 字段收 null 会校验失败）。
 */
@Data
public class BotSyncDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 跨源稳定文档 ID：content:{id} / answer:{id} / 政策业务 ID */
    private String docId;

    /** POST / ANSWER / POLICY */
    private String docKind;

    /** POST=contentId；ANSWER=questionId；POLICY=null */
    private Long contentId;

    /** ANSWER=answerId；其余 null */
    private Long answerId;

    private String title;

    private String content;

    private String createTime;

    private String updateTime;

    /** 水位线字段（与 updateTime 同值——QuantaBot SyncDoc 契约双字段） */
    private String updatedAt;

    /** active / deleted（墓碑——docId 与原记录一致） */
    private String status;
}
```

`demo0/src/main/java/com/quanta/demo0/controller/bot/vo/BotSyncPageVO.java`：

```java
package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 同步分页响应（items/hasMore——QuantaBot ContentSyncClient 契约）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotSyncPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BotSyncDocVO> items;

    private boolean hasMore;
}
```

`demo0/src/main/java/com/quanta/demo0/dto/BotPolicyDocDTO.java`：

```java
package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 政策文档录入 DTO（POST /bot/knowledge/policy-docs；upsert 语义）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BotPolicyDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务文档 ID（≤64 字符；已存在则更新并复活） */
    private String docId;

    private String title;

    private String content;
}
```

- [x] **Step 5: 建 Mapper（接口 + XML UNION）**

`demo0/src/main/java/com/quanta/demo0/mapper/BotContentSyncMapper.java`：

```java
package com.quanta.demo0.mapper;

import com.quanta.demo0.controller.bot.vo.BotSyncDocVO;
import com.quanta.demo0.entity.BotPolicyDoc;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * bot 内容源同步数据访问（C-3：三源 UNION + 政策文档 CRUD）。
 */
@Mapper
public interface BotContentSyncMapper {

    /**
     * 三源 UNION 分页（POST/ANSWER/POLICY；含墓碑；update_time >= since）。
     */
    List<BotSyncDocVO> selectSyncBatch(
            @Param("since") LocalDateTime since, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 三源总数（与 selectSyncBatch 同口径——不 join，行数不受影响）。
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM tb_content c WHERE (c.audit_status = 1 OR c.is_deleted = 1) AND c.update_time >= #{since}) +
              (SELECT COUNT(*) FROM tb_question_answer a WHERE (a.audit_status = 1 OR a.is_deleted = 1) AND a.update_time >= #{since}) +
              (SELECT COUNT(*) FROM tb_bot_policy_doc p WHERE p.update_time >= #{since})
            """)
    long countSync(@Param("since") LocalDateTime since);

    // ---------- 政策文档 CRUD ----------

    @Select("select * from tb_bot_policy_doc where doc_id = #{docId} limit 1")
    BotPolicyDoc selectByDocId(@Param("docId") String docId);

    @Insert("""
            insert into tb_bot_policy_doc (doc_id, title, content)
            values (#{docId}, #{title}, #{content})
            """)
    int insertPolicyDoc(BotPolicyDoc doc);

    /**
     * upsert 更新分支：不筛 is_deleted——已删文档再次录入即复活（下次 sync 推 active）。
     */
    @Update("""
            update tb_bot_policy_doc
            set title = #{title}, content = #{content}, is_deleted = 0, update_time = now()
            where doc_id = #{docId}
            """)
    int updatePolicyDocByDocId(BotPolicyDoc doc);

    /**
     * 软删（墓碑）：update_time=now 保证增量拉取不漏。
     */
    @Update("update tb_bot_policy_doc set is_deleted = 1, update_time = now() where doc_id = #{docId} and is_deleted = 0")
    int softDeletePolicyDocByDocId(@Param("docId") String docId);
}
```

（`insert` 需补 import `org.apache.ibatis.annotations.Insert`。）

`demo0/src/main/resources/mapper/BotContentSyncMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.quanta.demo0.mapper.BotContentSyncMapper">

    <!-- 三源 UNION：正常=审核通过可见；墓碑=已软删（docId 与原记录一致——bot 端靠它删 Qdrant 点） -->
    <select id="selectSyncBatch" resultType="com.quanta.demo0.controller.bot.vo.BotSyncDocVO">
        SELECT * FROM (
            SELECT
                CONCAT('content:', c.content_id)                                    AS docId,
                'POST'                                                               AS docKind,
                c.content_id                                                         AS contentId,
                NULL                                                                 AS answerId,
                IFNULL(c.title, '')                                                  AS title,
                IFNULL(c.content, '')                                                AS content,
                DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i:%s')                      AS createTime,
                DATE_FORMAT(c.update_time, '%Y-%m-%d %H:%i:%s')                      AS updateTime,
                DATE_FORMAT(c.update_time, '%Y-%m-%d %H:%i:%s')                      AS updatedAt,
                CASE WHEN c.is_deleted = 1 THEN 'deleted' ELSE 'active' END          AS status
            FROM tb_content c
            WHERE (c.audit_status = 1 OR c.is_deleted = 1)
              AND c.update_time &gt;= #{since}
            UNION ALL
            SELECT
                CONCAT('answer:', a.answer_id)                                       AS docId,
                'ANSWER'                                                             AS docKind,
                a.question_id                                                        AS contentId,
                a.answer_id                                                          AS answerId,
                IFNULL(q.title, '')                                                  AS title,
                IFNULL(a.content, '')                                                AS content,
                DATE_FORMAT(a.create_time, '%Y-%m-%d %H:%i:%s')                      AS createTime,
                DATE_FORMAT(a.update_time, '%Y-%m-%d %H:%i:%s')                      AS updateTime,
                DATE_FORMAT(a.update_time, '%Y-%m-%d %H:%i:%s')                      AS updatedAt,
                CASE WHEN a.is_deleted = 1 THEN 'deleted' ELSE 'active' END          AS status
            FROM tb_question_answer a
            LEFT JOIN tb_content q ON q.content_id = a.question_id
            WHERE (a.audit_status = 1 OR a.is_deleted = 1)
              AND a.update_time &gt;= #{since}
            UNION ALL
            SELECT
                p.doc_id                                                             AS docId,
                'POLICY'                                                             AS docKind,
                NULL                                                                 AS contentId,
                NULL                                                                 AS answerId,
                IFNULL(p.title, '')                                                  AS title,
                IFNULL(p.content, '')                                                AS content,
                DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s')                      AS createTime,
                DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i:%s')                      AS updateTime,
                DATE_FORMAT(p.update_time, '%Y-%m-%d %H:%i:%s')                      AS updatedAt,
                CASE WHEN p.is_deleted = 1 THEN 'deleted' ELSE 'active' END          AS status
            FROM tb_bot_policy_doc p
            WHERE p.update_time &gt;= #{since}
        ) t
        ORDER BY t.updateTime, t.docId
        LIMIT #{offset}, #{limit}
    </select>
</mapper>
```

- [x] **Step 6: 建 Service 接口与实现**

`demo0/src/main/java/com/quanta/demo0/service/BotContentSyncService.java`：

```java
package com.quanta.demo0.service;

import com.quanta.demo0.controller.bot.vo.BotSyncPageVO;
import com.quanta.demo0.dto.BotPolicyDocDTO;

/**
 * bot 内容源同步（C-3）：三源增量拉取 + 政策文档运营录入。
 */
public interface BotContentSyncService {

    /** 增量同步分页（since 支持 yyyy-MM-dd 与 yyyy-MM-dd HH:mm:ss 两种格式）。 */
    BotSyncPageVO getSync(String since, int pageNum, int pageSize);

    /** 政策文档 upsert（docId 已存在→更新并复活；不存在→新增）。 */
    void upsertPolicyDoc(BotPolicyDocDTO dto);

    /** 政策文档软删（墓碑——sync 流推 status=deleted）。 */
    void softDeletePolicyDoc(String docId);
}
```

`demo0/src/main/java/com/quanta/demo0/service/Impl/BotContentSyncServiceImpl.java`：

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.controller.bot.vo.BotSyncDocVO;
import com.quanta.demo0.controller.bot.vo.BotSyncPageVO;
import com.quanta.demo0.dto.BotPolicyDocDTO;
import com.quanta.demo0.entity.BotPolicyDoc;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.BotContentSyncMapper;
import com.quanta.demo0.service.BotContentSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 内容源同步实现（C-3）。
 *
 * since 语义：update_time >= since（宁重复勿遗漏——bot 端 upsert/删除幂等；
 * 水位线管理归 M5 定时摄取，本里程碑全量起点 since=1970-01-01）。
 */
@Service
@Slf4j
public class BotContentSyncServiceImpl implements BotContentSyncService {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int MAX_PAGE_SIZE = 200;

    @Autowired
    private BotContentSyncMapper botContentSyncMapper;

    @Override
    public BotSyncPageVO getSync(String since, int pageNum, int pageSize) {
        LocalDateTime sinceAt = parseSince(since);
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = botContentSyncMapper.countSync(sinceAt);
        List<BotSyncDocVO> items = botContentSyncMapper.selectSyncBatch(
                sinceAt, (page - 1) * size, size);
        return BotSyncPageVO.builder()
                .items(items)
                .hasMore((long) page * size < total)
                .build();
    }

    @Override
    public void upsertPolicyDoc(BotPolicyDocDTO dto) {
        validatePolicyDoc(dto);
        BotPolicyDoc existing = botContentSyncMapper.selectByDocId(dto.getDocId());
        BotPolicyDoc doc = BotPolicyDoc.builder()
                .docId(dto.getDocId())
                .title(dto.getTitle())
                .content(dto.getContent())
                .build();
        if (existing == null) {
            botContentSyncMapper.insertPolicyDoc(doc);
        } else {
            botContentSyncMapper.updatePolicyDocByDocId(doc);
        }
        log.info("政策文档 upsert：docId={}（{}）", dto.getDocId(),
                existing == null ? "新增" : "更新/复活");
    }

    @Override
    public void softDeletePolicyDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ContentFailedException("docId 不能为空");
        }
        botContentSyncMapper.softDeletePolicyDocByDocId(docId);
        log.info("政策文档软删（墓碑）：docId={}", docId);
    }

    // ---------- 内部 ----------

    /** since 双格式解析（日期→当日 00:00:00）；非法值直接 400 语义。 */
    private LocalDateTime parseSince(String since) {
        if (since == null || since.isBlank()) {
            throw new ContentFailedException("since 不能为空");
        }
        try {
            return LocalDateTime.parse(since, DATE_TIME);
        } catch (Exception ignored) {
            // 落入日期格式尝试
        }
        try {
            return LocalDate.parse(since, DATE_ONLY).atStartOfDay();
        } catch (Exception e) {
            throw new ContentFailedException("since 格式非法（支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）");
        }
    }

    private void validatePolicyDoc(BotPolicyDocDTO dto) {
        if (dto == null
                || dto.getDocId() == null || dto.getDocId().isBlank() || dto.getDocId().length() > 64
                || dto.getTitle() == null || dto.getTitle().isBlank() || dto.getTitle().length() > 200
                || dto.getContent() == null || dto.getContent().isBlank()) {
            throw new ContentFailedException("政策文档参数非法（docId≤64 字符、title≤200 字符、content 必填）");
        }
    }
}
```

（需补 import `java.time.LocalDate`。）

- [x] **Step 7: 建 Controller**

`demo0/src/main/java/com/quanta/demo0/controller/bot/BotContentController.java`：

```java
package com.quanta.demo0.controller.bot;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.controller.bot.vo.BotSyncPageVO;
import com.quanta.demo0.dto.BotPolicyDocDTO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.BotContentSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * bot 内容源接口（C-3；D6）。
 *
 * /bot/content/sync：BOT 角色（QuantaBot 摄取消费）；
 * /bot/knowledge/policy-docs：运营管理（政策录入/删除——upsert 复活语义）。
 */
@RestController
@RequestMapping("/bot")
@Slf4j
public class BotContentController {

    @Autowired
    private BotContentSyncService botContentSyncService;

    /**
     * C-3：三源增量同步（POST/ANSWER/POLICY + 墓碑）。摄取分页循环高频，宽松限流。
     */
    @GetMapping("/content/sync")
    @PreAuthorize("hasRole('BOT')")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotSyncPageVO> sync(
            @RequestParam String since,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(botContentSyncService.getSync(since, pageNum, pageSize));
    }

    /**
     * C-3：政策文档录入（upsert——docId 已存在则更新并复活）。
     */
    @PostMapping("/knowledge/policy-docs")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @RateLimit(scene = "bot-policy-write", limit = 20, windowSeconds = 60)
    public Result<Void> upsertPolicyDoc(@RequestBody BotPolicyDocDTO dto) {
        botContentSyncService.upsertPolicyDoc(dto);
        return Result.success();
    }

    /**
     * C-3：政策文档删除（软删→墓碑→下次同步 bot 删 Qdrant 点）。
     */
    @DeleteMapping("/knowledge/policy-docs/{docId}")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @RateLimit(scene = "bot-policy-write", limit = 20, windowSeconds = 60)
    public Result<Void> deletePolicyDoc(@PathVariable String docId) {
        botContentSyncService.softDeletePolicyDoc(docId);
        return Result.success();
    }
}
```

- [ ] **Step 8: 跑测试确认通过 + 回归已通过；提交未执行**

Run: `mvn -f demo0/pom.xml test -Dtest="BotContentSyncServiceImplTest"` → PASS（6 用例）；
Run: `mvn -f demo0/pom.xml test` → 全绿。

```bash
git -C demo0 add src/main/resources/db/dev-seed-incremental.sql src/main/java/com/quanta/demo0/entity/BotPolicyDoc.java src/main/java/com/quanta/demo0/mapper/BotContentSyncMapper.java src/main/resources/mapper/BotContentSyncMapper.xml src/main/java/com/quanta/demo0/controller/bot/vo/BotSyncDocVO.java src/main/java/com/quanta/demo0/controller/bot/vo/BotSyncPageVO.java src/main/java/com/quanta/demo0/dto/BotPolicyDocDTO.java src/main/java/com/quanta/demo0/service/BotContentSyncService.java src/main/java/com/quanta/demo0/service/Impl/BotContentSyncServiceImpl.java src/main/java/com/quanta/demo0/controller/bot/BotContentController.java src/test/java/com/quanta/demo0/service/BotContentSyncServiceImplTest.java
git -C demo0 commit -m "feat(bot): D6 内容源同步接口+政策文档表与录入通道（C-3；三源 UNION+墓碑语义）"
```

**执行注记**：
- since 非法固定抛 `ContentFailedException`；现有 `GlobalExceptionHandler.handleContentFailedException` 映射 `Result.code=400`，禁止改用会落到 500 兜底的 `IllegalArgumentException`；
- UNION 的 `ORDER BY t.updateTime, t.docId` 保证分页跨页稳定（同刻多条不漂移）；
- ANSWER 的 title 挂问题标题（LEFT JOIN）：问题被物理清理时 IFNULL 兜底空串，不炸对端 Pydantic。

---

> **批3 收尾**：Bot 侧墓碑闭环（Task 10）。

**前置**：Task 9 的 `status` 契约字段已定型；代码可在 demo0 真接口就绪前先行，真 Qdrant 验收放到后续联调计划。

---

### Task 10：D6——QuantaBot 侧墓碑消费（C-3 删除语义闭环）

**目标**：demo0 同步流里的软删墓碑（`status="deleted"`，docId 不变）在 bot 侧不再被当作正常内容 upsert，而是按稳定点 ID 从 Qdrant 删除——检索不再命中已删内容。C-3 契约的删除语义只有到这一步才真正闭环。

**现状事实**（Task 10 执行前已核实）：
- `QuantaBot/src/quanta_bot/infra/content_sync.py:19-59`：`SyncDoc` 9 字段，**无 `status`**；
- `content_sync.py:157-160`：`ContentIndex` 协议只有 `upsert_docs`；
- `content_sync.py:163-176`：`ingest_content` 拉全量 → `index.upsert_docs(all_docs)`，返回 `int`；
- `QuantaBot/src/quanta_bot/infra/qdrant_content.py:24-26`：`_point_id(doc) = str(uuid5(NAMESPACE_URL, f"quantabot:content:{doc.doc_kind}:{doc.doc_id}"))`——删除与写入同源即可精确定位；`QdrantContentIndex` **无 delete 方法**；
- 调用点全量：`src/quanta_bot/server.py:202-203`（手动摄取端点）、`tests/integration/test_rag_integration.py:33-34`、`tests/unit/infra/test_content_sync.py:143-184`（两个既有用例）——改返回签名必须三处同改。

**Files**：
- 改 `QuantaBot/src/quanta_bot/infra/content_sync.py`（SyncDoc 加 status、ContentIndex 加 delete_docs、ingest_content 分流）
- 改 `QuantaBot/src/quanta_bot/infra/qdrant_content.py`（QdrantContentIndex.delete_docs）
- 改 `QuantaBot/src/quanta_bot/server.py`（摄取端点解包）
- 改 `QuantaBot/tests/unit/infra/test_content_sync.py`、`QuantaBot/tests/unit/infra/test_qdrant_content.py`（新用例 + 替身扩展 + 既有用例解包）
- 改 `QuantaBot/tests/integration/test_rag_integration.py`（解包）

**Interfaces**：
- `SyncDoc.status: Literal["active", "deleted"]`（默认 `"active"`——FakeContentSource 与旧形状天然兼容）
- `ContentIndex.delete_docs(docs: Sequence[SyncDoc]) -> int`
- `ingest_content(source, index) -> tuple[int, int]`（返回 `(upserted, deleted)`）

- [x] **Step 1: 红——写失败测试**

`tests/unit/infra/test_content_sync.py` 追加（文件末尾 `_unused_httpx_guard` 之前）：

```python
class _SplitIndex:
    """摄取分流测试用计数索引（upsert/delete 双通道记录）。"""

    def __init__(self) -> None:
        self.upserted: list[SyncDoc] = []
        self.deleted: list[SyncDoc] = []

    async def upsert_docs(self, docs: list[SyncDoc]) -> int:
        self.upserted.extend(docs)
        return len(docs)

    async def delete_docs(self, docs: list[SyncDoc]) -> int:
        self.deleted.extend(docs)
        return len(docs)


async def test_sync_doc_defaults_active_and_accepts_tombstone() -> None:
    """SyncDoc 缺省 status=active；demo0 软删行（status="deleted"，docId 不变）可解析。"""
    active = SyncDoc.model_validate(
        {"docId": "content:1", "docKind": "POST", "updatedAt": "2026-09-01T00:00:00"}
    )
    tombstone = SyncDoc.model_validate(
        {
            "docId": "content:2",
            "docKind": "POST",
            "updatedAt": "2026-09-02T00:00:00",
            "status": "deleted",
        }
    )
    assert active.status == "active"
    assert tombstone.status == "deleted"


async def test_ingest_content_routes_tombstones_to_delete_docs() -> None:
    """墓碑分流：status=deleted 进 delete_docs，active 进 upsert_docs，返回 (upserted, deleted)。"""
    docs = [
        SyncDoc(doc_id="content:1", doc_kind="POST", updated_at="2026-09-01T00:00:00"),
        SyncDoc(
            doc_id="content:2",
            doc_kind="POST",
            updated_at="2026-09-02T00:00:00",
            status="deleted",
        ),
        SyncDoc(doc_id="policy-1", doc_kind="POLICY", updated_at="2026-09-03T00:00:00"),
    ]

    class _Source:
        async def fetch_sync(self, since: str, page_size: int = 50, page_num: int = 1):
            return list(docs), False

    index = _SplitIndex()
    result = await ingest_content(_Source(), index)  # type: ignore[arg-type]

    assert result == (2, 1)
    assert [d.doc_id for d in index.upserted] == ["content:1", "policy-1"]
    assert [d.doc_id for d in index.deleted] == ["content:2"]


async def test_ingest_content_prefers_active_over_tombstone_for_same_doc() -> None:
    """同 docId 既删又活（删后复活）：active 胜出，不误删刚复活的内容。"""
    docs = [
        SyncDoc(
            doc_id="content:9",
            doc_kind="POST",
            updated_at="2026-09-01T00:00:00",
            status="deleted",
        ),
        SyncDoc(doc_id="content:9", doc_kind="POST", updated_at="2026-09-02T00:00:00"),
    ]

    class _Source:
        async def fetch_sync(self, since: str, page_size: int = 50, page_num: int = 1):
            return list(docs), False

    index = _SplitIndex()
    result = await ingest_content(_Source(), index)  # type: ignore[arg-type]

    assert result == (1, 0)
    assert [d.doc_id for d in index.upserted] == ["content:9"]
    assert index.deleted == []
```

`tests/unit/infra/test_qdrant_content.py`：`_QdrantStub.__init__` 加 `self.deleted: list[str] = []`，类里追加方法：

```python
    async def delete(self, **kwargs) -> None:
        self.deleted.extend(kwargs["points_selector"].points)
```

文件末尾追加用例：

```python
async def test_qdrant_content_index_deletes_by_stable_point_ids() -> None:
    """墓碑删除：按 upsert 同源的稳定点 ID 删（doc_kind+doc_id → uuid5 精确定位）。"""
    client = _QdrantStub()
    index = QdrantContentIndex(client, "qb_content", _EmbeddingStub())  # type: ignore[arg-type]
    doc = SyncDoc(
        doc_id="policy-1",
        doc_kind="POLICY",
        title="奖助学金",
        content="奖助学金每年评审",
        updated_at="2026-09-01",
    )

    await index.upsert_docs([doc])
    upserted_id = str(client.upserted[-1].id)
    await index.delete_docs([doc])

    assert client.deleted == [upserted_id]
```

- [x] **Step 2: 绿——SyncDoc 加 status 字段**

`content_sync.py` `SyncDoc` 在 `updated_at` 字段（32 行）后追加：

```python
    status: Literal["active", "deleted"] = "active"
```

并把类 docstring 补一句：`墓碑契约：demo0 软删行以 status="deleted" 且 docId 不变进同步流——[C-3 删除语义，D6]。`

- [x] **Step 3: 绿——ingest_content 墓碑分流 + 协议加 delete_docs**

`content_sync.py` `ContentIndex` 协议整体替换：

```python
class ContentIndex(Protocol):
    """内容索引端口（QdrantContentIndex 实现；摄取编排消费 upsert_docs + delete_docs）。"""

    async def upsert_docs(self, docs: Sequence[SyncDoc]) -> int: ...

    async def delete_docs(self, docs: Sequence[SyncDoc]) -> int: ...
```

`ingest_content` 整体替换：

```python
async def ingest_content(source: ContentSource, index: ContentIndex) -> tuple[int, int]:
    """摄取编排：源全量（分页循环至 has_more=False）→ 墓碑分流。

    active → index.upsert_docs；status="deleted"（demo0 软删墓碑，docId 不变）→ index.delete_docs。
    返回 (upserted, deleted)；同一 docId 同流中既有墓碑又有 active 行（删后复活）时 active 胜出。
    """
    active: list[SyncDoc] = []
    tombstoned: dict[tuple[str, str], SyncDoc] = {}
    since = "1970-01-01"  # 全量起点（增量水位线随 M5 定时摄取再引入）
    page_num = 1
    while True:
        docs, has_more = await source.fetch_sync(since, page_size=50, page_num=page_num)
        for doc in docs:
            if doc.status == "deleted":
                tombstoned[(doc.doc_kind, doc.doc_id)] = doc
            else:
                active.append(doc)
        if not has_more or not docs:
            break
        page_num += 1
    active_keys = {(doc.doc_kind, doc.doc_id) for doc in active}
    to_delete = [doc for key, doc in tombstoned.items() if key not in active_keys]
    deleted = 0
    if to_delete:
        deleted = await index.delete_docs(to_delete)
    if not active:
        return 0, deleted
    return await index.upsert_docs(active), deleted
```

- [x] **Step 4: 绿——QdrantContentIndex.delete_docs**

`qdrant_content.py` 顶部 models 导入改为：

```python
from qdrant_client.models import FieldCondition, Filter, MatchValue, PointIdsList, PointStruct
```

`upsert_docs` 方法后追加：

```python
    async def delete_docs(self, docs: Sequence[SyncDoc]) -> int:
        """按稳定点 ID 删除墓碑文档（与 upsert 同源 uuid5——docId 不变即可精确定位；C-3/D6）。"""
        if not docs:
            return 0
        await self._client.delete(
            collection_name=self._collection,
            points_selector=PointIdsList(points=[_point_id(doc) for doc in docs]),
        )
        return len(docs)
```

- [x] **Step 5: 伴随修改——三处调用点解包**

`src/quanta_bot/server.py:202-203`：

```python
        upserted, deleted = await ingest_content(runtime.rag.source, runtime.rag.index)
        return {"ingested": upserted, "deleted": deleted}
```

`tests/integration/test_rag_integration.py:33-34`：

```python
    upserted, _ = await ingest_content(rag_stack.source, rag_stack.index)
    assert upserted >= 8
```

`tests/unit/infra/test_content_sync.py` 既有两用例改解包（`_FakeIndex` 同时补 `delete_docs` 方法——与 `_SplitIndex` 同形状，保持 Protocol 完整实现）：

```python
    async def delete_docs(self, docs: list[SyncDoc]) -> int:
        self.deleted.extend(docs)
        return len(docs)
```

`_FakeIndex.__init__` 加 `self.deleted: list[SyncDoc] = []`；两个用例断言改：

```python
    upserted, deleted = await ingest_content(FakeContentSource(), index)  # type: ignore[arg-type]
    docs, _ = await FakeContentSource().fetch_sync(since="1970-01-01")
    assert upserted == len(docs) == len(index.upserted)
    assert deleted == 0  # 合成样本无墓碑
```

（分页推进用例同改：`upserted, deleted = await ingest_content(...)`，断言 `assert (upserted, deleted) == (2, 0)`，`index.upserted`/`pageNum` 断言不变。）

- [x] **Step 6: 验证**

```bash
uv run pytest tests/unit/infra/test_content_sync.py tests/unit/infra/test_qdrant_content.py -q
uv run pytest tests/unit -q
```

（integration 档 `uv run pytest tests/integration/test_rag_integration.py -q` 需真 Qdrant，随 Task 13 联调冒烟一起跑。）

- [ ] **Step 7: 提交**

```bash
git add QuantaBot/src/quanta_bot/infra/content_sync.py QuantaBot/src/quanta_bot/infra/qdrant_content.py QuantaBot/src/quanta_bot/server.py QuantaBot/tests/unit/infra/test_content_sync.py QuantaBot/tests/unit/infra/test_qdrant_content.py QuantaBot/tests/integration/test_rag_integration.py
git commit -m "feat(bot): D6 墓碑消费——SyncDoc.status + ingest_content 分流 + QdrantContentIndex.delete_docs（C-3 删除语义闭环）"
```

**执行注记**：
- qdrant-client 的 `delete` 接受 `PointIdsList(points=[str-uuid])`——与 M2 既有 models 导入同源，无需新依赖；
- 墓碑行 payload 中 title/content 可能被 demo0 置空（IFNULL 兜底）——delete 路径不读 payload，无影响；
- FakeContentSource 合成样本全部走 `status` 默认值 active，不需要改动。

---

## 后续计划

小程序 D3、用户可见昵称“框框”、真实端到端联调、17 场景回归与文档收口，转入 `QuantaBot/docs/plans/demo0-Bot主链路接入-前端改名与验收.md`（Task 11-14）。

本计划的完成门是 Task 1-10 各自测试全绿，并向后续计划提供 `mentionBot/isBot`、BOT service token、MQ 事件、只读/同步接口和墓碑消费能力；不在本计划内提前宣称真联调完成。
