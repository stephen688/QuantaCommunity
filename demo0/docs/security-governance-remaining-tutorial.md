# 安全治理计划剩余部分分步教学

> 对应原计划：`docs/security-governance-plan.md`
>
> 适合读者：第一次系统学习 Spring Security、审计日志和 Redis 限流。
>
> 教学约定：业务代码由你按章节修改；数据库操作和测试仍由 Codex 负责。不要跳章，因为后面的代码会依赖前面创建的类。

## 一、现在已经完成了什么

以下内容已经完成，不在本文重复实现：

- HTTP 与 WebSocket 共用 `TokenAuthenticationService`。
- JWT、Redis 登录态、封禁状态和数据库账号状态统一校验。
- Spring Security Filter、401、403、CORS 和无 Session 配置。
- `USER`、`VERIFIED_USER`、三种管理角色和十种权限。
- 内容、运营、事件管理接口的方法级权限。
- 发布帖子、回答、评论需要 `VERIFIED_USER`。
- 旧 `@RequireAuth` 和 `AuthInterceptor` 已删除。
- `user_role` 表和旧管理员迁移已经完成。

本文剩余路线：

```text
阶段3收尾：封禁后停止WebSocket实时推送
    ↓
阶段4：管理员审计闭环和角色管理
    ↓
阶段5：Redis Lua限流和429
    ↓
阶段6：管理端、小程序同步权限语义
    ↓
阶段7：删除旧JWT管理员字段和重复安全逻辑
    ↓
最终综合验收
```

---

# 二、阶段 3 收尾：封禁后停止实时推送

## 第 1 节：创建用户实时推送状态服务

### 这一步干什么

用户连接 WebSocket 时虽然检查过封禁状态，但连接建立后可以保持很久。用户在连接期间被管理员封禁，旧连接不会自动重新认证，因此发送每条实时通知前还要检查一次账号状态。

这个检查不能使用 `BaseContext`、Token 或 `@PreAuthorize`，因为 `NotificationConsumer` 是 RabbitMQ 主动调用的，没有当前登录用户。

### 1. 修改 UserMapper

文件：`src/main/java/com/quanta/demo0/mapper/UserMapper.java`

在接口末尾增加：

```java
/**
 * 查询用户账号状态。
 *
 * 只查询实时推送需要的account_status字段，
 * 避免为了判断封禁状态而查询整条用户信息。
 *
 * @param userId 用户ID
 * @return 0-正常，1-封禁；用户不存在时返回null
 */
@Select("""
        select account_status
        from tb_user
        where id = #{userId}
          and is_deleted = 0
        """)
Integer getAccountStatusById(Long userId);
```

### 2. 新建 UserAccessStateService

文件：`src/main/java/com/quanta/demo0/service/UserAccessStateService.java`

```java
package com.quanta.demo0.service;

/**
 * 用户访问状态服务。
 *
 * 该服务不依赖HTTP请求、Token、BaseContext或SecurityContext，
 * 可以安全地被MQ消费者和内部任务调用。
 */
public interface UserAccessStateService {

    /**
     * 判断用户是否可以接收WebSocket实时推送。
     *
     * @param userId 接收通知的用户ID
     * @return true-允许推送，false-跳过推送
     */
    boolean canReceiveRealtimePush(Long userId);
}
```

### 3. 新建实现类

文件：`src/main/java/com/quanta/demo0/service/Impl/UserAccessStateServiceImpl.java`

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.service.UserAccessStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户访问状态服务实现类。
 *
 * Redis用于快速发现刚刚被封禁的用户，
 * 数据库作为最终账号状态来源。
 */
@Service
@Slf4j
public class UserAccessStateServiceImpl
        implements UserAccessStateService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean canReceiveRealtimePush(Long userId) {
        if (userId == null) {
            return false;
        }

        String bannedKey =
                RedisConstants.USER_BANNED_KEY + userId;

        try {
            Boolean banned =
                    stringRedisTemplate.hasKey(bannedKey);

            if (Boolean.TRUE.equals(banned)) {
                return false;
            }
        } catch (Exception exception) {
            /*
             * Redis异常时不能直接认为用户正常，
             * 继续使用数据库状态兜底。
             */
            log.warn("读取Redis封禁状态失败，将回退数据库检查");
        }

        try {
            Integer accountStatus =
                    userMapper.getAccountStatusById(userId);

            /*
             * 只有明确查询到正常状态0才允许推送。
             * 用户不存在、已删除和未知状态都不推送。
             */
            return Integer.valueOf(0)
                    .equals(accountStatus);
        } catch (Exception exception) {
            /*
             * 实时提醒不是核心数据。
             * 状态无法确认时宁可不推送，数据库通知仍然保留。
             */
            log.error("查询用户账号状态失败，跳过实时推送", exception);
            return false;
        }
    }
}
```

## 第 2 节：接入 NotificationConsumer

### 以前为什么不够

当前顺序是：保存数据库通知，然后无条件调用 `convertAndSendToUser()`。封禁用户的旧 WebSocket 连接因此还能收到消息。

### 1. 增加依赖

文件：`src/main/java/com/quanta/demo0/mq/consumer/NotificationConsumer.java`

增加 import：

```java
import com.quanta.demo0.service.UserAccessStateService;
```

在 `simpMessagingTemplate` 后增加：

```java
/**
 * MQ线程没有SecurityContext，
 * 因此通过独立服务检查接收人的实时推送资格。
 */
@Autowired
private UserAccessStateService userAccessStateService;
```

### 2. 修改 pushWebSocketBestEffort

在该方法 `try` 的第一行增加：

```java
Long recipientUserId = message.getRecipientUserId();

/*
 * 数据库通知已经保存成功。
 * 封禁只阻止实时推送，不删除通知事实，也不触发MQ重试。
 */
if (!userAccessStateService
        .canReceiveRealtimePush(recipientUserId)) {
    log.info(
            "接收用户当前不可实时推送，跳过WebSocket通知，eventId={}",
            message.getEventId()
    );
    return;
}
```

原来的发送代码改为复用 `recipientUserId`：

```java
simpMessagingTemplate.convertAndSendToUser(
        String.valueOf(recipientUserId),
        "/queue/notifications",
        pushVO
);
```

最终顺序必须是：

```text
保存数据库通知并完成Inbox
    ↓
检查接收人是否允许实时推送
    ↓
正常：发送WebSocket
封禁：跳过WebSocket
    ↓
两种情况都ACK消息
```

绝对不要把状态检查放到 `saveAndMarkSuccess()` 前并直接结束整个消费者，否则封禁用户连数据库通知也收不到，破坏了通知事实记录。

### 本节验收（由 Codex 执行）

- 正常用户：保存通知、WebSocket 推送、ACK。
- Redis 有封禁 Key：保存通知、不推送、ACK。
- Redis 没有 Key但数据库为封禁：不推送、ACK。
- Redis 异常：回退数据库。
- 数据库状态查询异常：不推送，但仍 ACK，不进入 RETRYING 或 DEAD。
- MQ 消费线程没有 SecurityContext 时不抛 `AuthenticationCredentialsNotFoundException`。

---

# 三、阶段 4：管理员审计闭环

## 先理解：审计日志不是普通业务日志

普通日志通常回答“程序发生了什么”，审计日志要回答：

```text
谁
在什么时候
通过哪个请求
对哪个对象
做了什么高风险操作
操作前后发生了什么变化
最终成功还是失败
```

不能直接把 Controller 参数完整转成 JSON，因为里面可能包含 Token、身份证信息、长正文或上传内容。

## 第 3 节：创建审计表

数据库由 Codex 执行。先把下面 SQL 追加到：

`src/main/resources/db/V_security_governance.sql`

```sql
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计日志ID',
    `request_id` VARCHAR(64) NOT NULL COMMENT '请求关联ID',
    `operator_id` BIGINT NOT NULL COMMENT '操作管理员ID',
    `operator_roles` VARCHAR(512) NOT NULL COMMENT '操作时角色快照',
    `action` VARCHAR(64) NOT NULL COMMENT '操作代码',
    `target_type` VARCHAR(64) NOT NULL COMMENT '目标类型',
    `target_id` VARCHAR(128) DEFAULT NULL COMMENT '目标ID',
    `http_method` VARCHAR(16) DEFAULT NULL COMMENT 'HTTP方法',
    `request_path` VARCHAR(512) DEFAULT NULL COMMENT '请求路径',
    `before_summary` TEXT DEFAULT NULL COMMENT '操作前脱敏摘要',
    `after_summary` TEXT DEFAULT NULL COMMENT '操作后脱敏摘要',
    `result_status` VARCHAR(16) NOT NULL COMMENT 'SUCCESS或FAILED',
    `error_code` VARCHAR(64) DEFAULT NULL COMMENT '失败错误码',
    `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '脱敏失败原因',
    `client_ip` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    `user_agent` VARCHAR(512) DEFAULT NULL COMMENT '客户端信息',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_audit_operator_time` (`operator_id`, `created_at`),
    KEY `idx_audit_action_time` (`action`, `created_at`),
    KEY `idx_audit_target` (`target_type`, `target_id`, `created_at`),
    KEY `idx_audit_result_time` (`result_status`, `created_at`),
    KEY `idx_audit_request_id` (`request_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='管理员高风险操作审计日志';
```

验证脚本 `V_security_governance_verify.sql` 增加：

```sql
SHOW CREATE TABLE admin_audit_log;

SELECT index_name, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'admin_audit_log'
ORDER BY index_name, seq_in_index;
```

## 第 4 节：建立审计基础对象

### 1. 新建动作常量

文件：`src/main/java/com/quanta/demo0/constant/AdminAuditActionConstants.java`

```java
package com.quanta.demo0.constant;

/**
 * 管理员高风险操作代码。
 */
public final class AdminAuditActionConstants {

    public static final String CONTENT_AUDIT = "CONTENT_AUDIT";
    public static final String CONTENT_DELETE = "CONTENT_DELETE";
    public static final String REPORT_HANDLE = "REPORT_HANDLE";
    public static final String IDENTITY_AUDIT = "IDENTITY_AUDIT";
    public static final String USER_BAN = "USER_BAN";
    public static final String USER_UNBAN = "USER_UNBAN";
    public static final String EVENT_REPLAY = "EVENT_REPLAY";
    public static final String ROLE_GRANT = "ROLE_GRANT";
    public static final String ROLE_REVOKE = "ROLE_REVOKE";

    private AdminAuditActionConstants() {
    }
}
```

### 2. 新建审计实体

文件：`src/main/java/com/quanta/demo0/entity/AdminAuditLog.java`

```java
package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员审计日志。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLog {

    private Long id;
    private String requestId;
    private Long operatorId;
    private String operatorRoles;
    private String action;
    private String targetType;
    private String targetId;
    private String httpMethod;
    private String requestPath;
    private String beforeSummary;
    private String afterSummary;
    private String resultStatus;
    private String errorCode;
    private String errorMessage;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createdAt;
}
```

### 3. 新建 Mapper

文件：`src/main/java/com/quanta/demo0/mapper/AdminAuditLogMapper.java`

```java
package com.quanta.demo0.mapper;

import com.quanta.demo0.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * 管理员审计日志数据访问接口。
 */
@Mapper
public interface AdminAuditLogMapper {

    @Insert("""
            insert into admin_audit_log (
                request_id, operator_id, operator_roles,
                action, target_type, target_id,
                http_method, request_path,
                before_summary, after_summary,
                result_status, error_code, error_message,
                client_ip, user_agent, created_at
            ) values (
                #{requestId}, #{operatorId}, #{operatorRoles},
                #{action}, #{targetType}, #{targetId},
                #{httpMethod}, #{requestPath},
                #{beforeSummary}, #{afterSummary},
                #{resultStatus}, #{errorCode}, #{errorMessage},
                #{clientIp}, #{userAgent}, #{createdAt}
            )
            """)
    @Options(
            useGeneratedKeys = true,
            keyProperty = "id"
    )
    int insert(AdminAuditLog auditLog);
}
```

## 第 5 节：建立审计上下文和记录器

### 为什么分成功和失败两个记录器

- 成功日志必须和业务操作处于同一个事务：审计写失败，业务也回滚。
- 失败日志必须使用新事务：原业务事务回滚后，失败痕迹仍能保存。

不要只写一个 `try/catch` 后统一插入，否则很容易出现“业务已经回滚，日志却显示 SUCCESS”。

### 1. 新建 AdminAuditRecorder

文件：`src/main/java/com/quanta/demo0/service/AdminAuditRecorder.java`

```java
package com.quanta.demo0.service;

/**
 * 管理员审计记录器。
 */
public interface AdminAuditRecorder {

    void recordSuccess(
            String action,
            String targetType,
            String targetId,
            String beforeSummary,
            String afterSummary
    );

    void recordFailure(
            String action,
            String targetType,
            String targetId,
            Throwable throwable
    );
}
```

### 2. 实现时必须遵守的结构

实现类建议拆成：

```text
AdminAuditRecorderImpl
    recordSuccess()：Propagation.MANDATORY
    表示外面必须已经有业务事务

AdminAuditFailureWriter
    writeFailed()：Propagation.REQUIRES_NEW
    表示使用独立新事务保存失败日志
```

成功方法推荐签名：

```java
@Transactional(propagation = Propagation.MANDATORY)
public void recordSuccess(...) {
    AdminAuditLog auditLog = buildCommonAuditLog();
    auditLog.setResultStatus("SUCCESS");
    auditLog.setBeforeSummary(limitSummary(beforeSummary));
    auditLog.setAfterSummary(limitSummary(afterSummary));

    if (adminAuditLogMapper.insert(auditLog) != 1) {
        throw new IllegalStateException("管理员审计日志写入失败");
    }
}
```

失败写入器推荐签名：

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void writeFailed(AdminAuditLog auditLog) {
    adminAuditLogMapper.insert(auditLog);
}
```

`buildCommonAuditLog()` 从以下位置取值：

- 操作人：`SecurityContextHolder.getContext().getAuthentication()` 中的 `AuthenticatedUser`。
- 角色快照：`authenticatedUser.getRoles()` 排序后使用英文逗号连接。
- 请求：`RequestContextHolder.getRequestAttributes()`。
- `requestId`：优先读取请求头 `X-Request-Id`，没有时生成 UUID。
- 路径：只记录 `request.getRequestURI()`，不要拼接 query string。
- IP：优先可信反向代理配置下的转发头，否则使用 `getRemoteAddr()`；不要盲目信任任意客户端传入的 `X-Forwarded-For`。
- 摘要最大长度建议 2000，错误信息最大长度 1000。

禁止写入摘要的内容：Token、微信 code、密码、密钥、完整证件号、上传内容、帖子全文和 MQ payload。

## 第 6 节：先给“封禁用户”接入成功审计

### 以前为什么不行

`AdminUserServiceImpl.banUser()` 当前没有事务。数据库已经封禁，但审计插入失败时，无法把封禁一起回滚。

### 修改位置

文件：`src/main/java/com/quanta/demo0/service/Impl/AdminUserServiceImpl.java`

增加 import：

```java
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.service.AdminAuditRecorder;
import org.springframework.transaction.annotation.Transactional;
```

增加依赖：

```java
@Autowired
private AdminAuditRecorder adminAuditRecorder;
```

在 `banUser()` 上增加：

```java
@Transactional
@Override
public void banUser(Long userId) {
```

数据库和 Redis 操作完成后增加：

```java
adminAuditRecorder.recordSuccess(
        AdminAuditActionConstants.USER_BAN,
        "USER",
        String.valueOf(userId),
        "accountStatus=" + user.getAccountStatus(),
        "accountStatus=1"
);
```

`unbanUser()` 同样增加 `@Transactional`，并记录：

```java
adminAuditRecorder.recordSuccess(
        AdminAuditActionConstants.USER_UNBAN,
        "USER",
        String.valueOf(userId),
        "accountStatus=" + user.getAccountStatus(),
        "accountStatus=0"
);
```

注意：MySQL 操作可以回滚，Redis 删除不能跟着 MySQL 自动回滚。更稳妥的最终版本应把 Redis 登录态清理和封禁 Key 更新放到事务提交后的回调中；数据库提交前只处理数据库状态和成功审计。

## 第 7 节：接入失败审计 AOP

创建 `@AdminAudit` 注解，只标记 Controller 的高风险入口。AOP 只负责失败记录；成功记录仍由业务事务中的 `AdminAuditRecorder` 显式写入。

文件：`src/main/java/com/quanta/demo0/annotation/AdminAudit.java`

```java
package com.quanta.demo0.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理员高风险操作审计标记。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    String action();

    String targetType();

    /**
     * 目标ID的SpEL表达式，例如#userId。
     */
    String targetId() default "";
}
```

给 `pom.xml` 增加 AOP 依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

`AdminAuditAspect` 使用 `@Around("@annotation(adminAudit)")`：

```java
try {
    return joinPoint.proceed();
} catch (Throwable throwable) {
    try {
        adminAuditRecorder.recordFailure(
                adminAudit.action(),
                adminAudit.targetType(),
                resolveTargetId(joinPoint, adminAudit.targetId()),
                throwable
        );
    } catch (Exception auditException) {
        /*
         * 失败审计本身失败时记录严重日志，
         * 但不能覆盖最初的业务异常。
         */
        log.error("管理员失败审计写入异常", auditException);
    }
    throw throwable;
}
```

给封禁入口增加：

```java
@AdminAudit(
        action = AdminAuditActionConstants.USER_BAN,
        targetType = "USER",
        targetId = "#userId"
)
@PreAuthorize("hasAuthority('" + PermissionConstants.USER_BAN + "')")
@PostMapping("/ban/{userId}")
public Result ban(@PathVariable Long userId) {
```

解封、审核、删除、举报处理、身份审核和事件重放按同样模式接入。每个对应 Service 方法必须先确认存在事务，再写 SUCCESS 审计。

## 第 8 节：批量覆盖高风险操作

按下面矩阵逐个完成，不要一次改完再测试：

| Controller 操作 | action | targetType | 成功摘要示例 |
| --- | --- | --- | --- |
| 帖子/回答/评论审核 | `CONTENT_AUDIT` | `CONTENT`/`ANSWER`/`COMMENT` | `PENDING -> APPROVED` |
| 管理员删除内容 | `CONTENT_DELETE` | 对应类型 | `deleted=0 -> 1` |
| 举报处理 | `REPORT_HANDLE` | `REPORT` | `PENDING -> HANDLED` |
| 身份审核 | `IDENTITY_AUDIT` | `IDENTITY_AUTH` | `PENDING -> APPROVED` |
| 封禁/解封 | `USER_BAN`/`USER_UNBAN` | `USER` | `0 -> 1` / `1 -> 0` |
| 事件重放 | `EVENT_REPLAY` | `OUTBOX`/`INBOX` | `DEAD -> PENDING` |
| 角色授予/撤销 | `ROLE_GRANT`/`ROLE_REVOKE` | `USER_ROLE` | `beforeRoles -> afterRoles` |

每完成一种操作，由 Codex 跑三类测试：成功有 SUCCESS、失败有 FAILED、业务回滚没有错误 SUCCESS。

## 第 9 节：实现角色授予和撤销

角色管理不能让前端提交任意字符串。只允许：

```java
RoleConstants.CONTENT_AUDITOR
RoleConstants.OPERATIONS_ADMIN
RoleConstants.SUPER_ADMIN
```

给 `UserRoleMapper` 增加：

```java
@Insert("""
        insert ignore into user_role (
            user_id, role_code, created_by
        ) values (
            #{userId}, #{roleCode}, #{createdBy}
        )
        """)
int grantRole(
        @Param("userId") Long userId,
        @Param("roleCode") String roleCode,
        @Param("createdBy") Long createdBy
);

@Delete("""
        delete from user_role
        where user_id = #{userId}
          and role_code = #{roleCode}
        """)
int revokeRole(
        @Param("userId") Long userId,
        @Param("roleCode") String roleCode
);
```

新增 `AdminRoleController`：

```java
@RestController
@RequestMapping("/admin/roles")
@Slf4j
public class AdminRoleController {

    @Autowired
    private AdminRoleService adminRoleService;

    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_MANAGE + "')")
    @PostMapping("/{userId}/{roleCode}")
    public Result grantRole(
            @PathVariable Long userId,
            @PathVariable String roleCode
    ) {
        adminRoleService.grantRole(userId, roleCode);
        return Result.success();
    }

    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_MANAGE + "')")
    @DeleteMapping("/{userId}/{roleCode}")
    public Result revokeRole(
            @PathVariable Long userId,
            @PathVariable String roleCode
    ) {
        adminRoleService.revokeRole(userId, roleCode);
        return Result.success();
    }
}
```

`AdminRoleServiceImpl` 必须：

1. 校验用户存在。
2. 校验角色在管理角色白名单中。
3. 修改 `user_role`。
4. 在同一事务记录成功审计。
5. 事务提交后删除 `LOGIN_USER_KEY + userId`，让旧 Token 立即失效。
6. 禁止操作人撤销自己唯一的 `SUPER_ADMIN`，避免系统没有最高管理员。

`SecurityConfiguration` 在 `/admin/**` 兜底规则前增加：

```java
.requestMatchers(
        "/admin/roles/**",
        "/admin/audit-logs/**"
).authenticated()
```

具体权限仍由 Controller 的 `@PreAuthorize` 判断。

## 第 10 节：审计日志查询接口

新增：

```text
GET /admin/audit-logs/page
GET /admin/audit-logs/{id}
```

两个方法都使用：

```java
@PreAuthorize(
        "hasAuthority('" +
                PermissionConstants.AUDIT_LOG_READ +
                "')"
)
```

查询 DTO 至少包含：`pageNum`、`pageSize`、`operatorId`、`action`、`targetType`、`targetId`、`resultStatus`、`requestId`、`startTime`、`endTime`。

Mapper XML 使用动态 `<where>`，但必须限制：

- `pageSize` 最大 100。
- 默认只查最近 30 天。
- 排序固定为 `created_at desc, id desc`。
- 不提供删除接口。
- 详情 VO 不返回任何原始请求体。

阶段 4 完成后再进入限流，不要同时修改审计事务和 Redis 限流。

---

# 四、阶段 5：Redis Lua 限流

## 第 11 节：先实现原子限流核心

### 以前为什么不行

如果 Java 分两次执行 `INCR` 和 `EXPIRE`：

```text
INCR成功
    ↓
应用在EXPIRE前宕机
    ↓
Key永不过期
    ↓
用户可能永久被限流
```

Lua 脚本在 Redis 内一次执行完“加一、设置过期时间、计算剩余次数”，中间不会被其他命令打断。

### 1. 新建 Lua 脚本

文件：`src/main/resources/lua/rate_limit.lua`

```lua
local current = redis.call('INCR', KEYS[1])

if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

local ttl = redis.call('TTL', KEYS[1])
local limit = tonumber(ARGV[2])
local allowed = 0

if current <= limit then
    allowed = 1
end

local remaining = limit - current
if remaining < 0 then
    remaining = 0
end

return {allowed, remaining, ttl}
```

### 2. 新建返回对象

文件：`src/main/java/com/quanta/demo0/security/RateLimitDecision.java`

```java
package com.quanta.demo0.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一次限流判断结果。
 */
@Data
@AllArgsConstructor
public class RateLimitDecision {

    private boolean allowed;
    private long remaining;
    private long retryAfterSeconds;
}
```

### 3. 新建 RateLimitService

文件：`src/main/java/com/quanta/demo0/service/RateLimitService.java`

```java
package com.quanta.demo0.service;

import com.quanta.demo0.security.RateLimitDecision;

/**
 * Redis原子限流服务。
 */
public interface RateLimitService {

    RateLimitDecision check(
            String scene,
            String subject,
            int limit,
            int windowSeconds,
            boolean failClosed
    );
}
```

### 4. 新建实现类

文件：`src/main/java/com/quanta/demo0/service/Impl/RateLimitServiceImpl.java`

```java
package com.quanta.demo0.service.Impl;

import com.quanta.demo0.security.RateLimitDecision;
import com.quanta.demo0.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis Lua限流服务实现类。
 */
@Service
@Slf4j
public class RateLimitServiceImpl
        implements RateLimitService {

    private static final DefaultRedisScript<List>
            RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(
                new ClassPathResource("lua/rate_limit.lua")
        );
        RATE_LIMIT_SCRIPT.setResultType(List.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RateLimitDecision check(
            String scene,
            String subject,
            int limit,
            int windowSeconds,
            boolean failClosed
    ) {
        String key = "security:rate-limit:"
                + scene + ":" + subject;

        try {
            List<Long> result = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(windowSeconds),
                    String.valueOf(limit)
            );

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Redis限流结果格式错误");
            }

            return new RateLimitDecision(
                    result.get(0) == 1L,
                    result.get(1),
                    Math.max(result.get(2), 1L)
            );
        } catch (Exception exception) {
            log.error("Redis限流检查失败，scene={}", scene, exception);

            if (failClosed) {
                return new RateLimitDecision(false, 0, 1);
            }

            return new RateLimitDecision(true, limit, 0);
        }
    }
}
```

Key 中的 `subject` 只能使用 userId、HMAC 摘要或不可逆 IP 摘要，不能放 Token、微信 code、openid 明文或完整 IP。

## 第 12 节：统一返回 HTTP 429

### 1. 新建异常

文件：`src/main/java/com/quanta/demo0/exception/RateLimitExceededException.java`

```java
package com.quanta.demo0.exception;

import lombok.Getter;

/**
 * 请求超过安全限流阈值。
 */
@Getter
public class RateLimitExceededException
        extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(
            String message,
            long retryAfterSeconds
    ) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
```

### 2. 修改 GlobalExceptionHandler

增加 import：

```java
import jakarta.servlet.http.HttpServletResponse;
```

增加处理方法：

```java
/**
 * 限流必须同时返回HTTP 429和Result.code=429。
 */
@ExceptionHandler(RateLimitExceededException.class)
public Result<Void> handleRateLimitExceeded(
        RateLimitExceededException exception,
        HttpServletResponse response
) {
    response.setStatus(429);
    response.setHeader(
            "Retry-After",
            String.valueOf(exception.getRetryAfterSeconds())
    );

    return Result.error(429, exception.getMessage());
}
```

只设置 `Result.code=429` 不够，因为 Axios 和小程序首先看到的是 HTTP 状态码。

## 第 13 节：增加注解式用户限流

### 1. 新建注解

文件：`src/main/java/com/quanta/demo0/annotation/RateLimit.java`

```java
package com.quanta.demo0.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 当前登录用户维度的接口限流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    String scene();

    int limit();

    int windowSeconds();

    /**
     * Redis异常时是否拒绝请求。
     */
    boolean failClosed() default true;
}
```

### 2. 新建 RateLimitAspect

文件：`src/main/java/com/quanta/demo0/aop/RateLimitAspect.java`

核心逻辑：

```java
@Around("@annotation(rateLimit)")
public Object checkRateLimit(
        ProceedingJoinPoint joinPoint,
        RateLimit rateLimit
) throws Throwable {
    Authentication authentication =
            SecurityContextHolder
                    .getContext()
                    .getAuthentication();

    if (authentication == null
            || !(authentication.getPrincipal()
            instanceof AuthenticatedUser authenticatedUser)) {
        throw new AuthenticationCredentialsNotFoundException(
                "当前用户未登录"
        );
    }

    RateLimitDecision decision = rateLimitService.check(
            rateLimit.scene(),
            String.valueOf(authenticatedUser.getUserId()),
            rateLimit.limit(),
            rateLimit.windowSeconds(),
            rateLimit.failClosed()
    );

    if (!decision.isAllowed()) {
        throw new RateLimitExceededException(
                "操作过于频繁，请稍后再试",
                decision.getRetryAfterSeconds()
        );
    }

    return joinPoint.proceed();
}
```

这个 AOP 只能用于 HTTP 用户入口，不能加到 MQ、定时任务和内部 Service 上。

### 3. 第一批接入位置

```java
// ContentController.publish
@RateLimit(
        scene = "content-publish",
        limit = 5,
        windowSeconds = 60
)

// AnswerController.publishAnswer
@RateLimit(
        scene = "answer-publish",
        limit = 15,
        windowSeconds = 60
)

// CommentController.sendComment
@RateLimit(
        scene = "comment-publish",
        limit = 15,
        windowSeconds = 60
)

// 帖子和评论举报接口
@RateLimit(
        scene = "report-submit",
        limit = 10,
        windowSeconds = 600
)

// CommonController.upload
@RateLimit(
        scene = "file-upload",
        limit = 20,
        windowSeconds = 60
)
```

管理端高风险操作使用更严格配置：

```java
// 事件重放
@RateLimit(
        scene = "event-replay",
        limit = 3,
        windowSeconds = 300,
        failClosed = true
)

// 用户封禁、解封、角色修改
@RateLimit(
        scene = "security-management",
        limit = 10,
        windowSeconds = 300,
        failClosed = true
)
```

顺序上，`@PreAuthorize` 必须先证明管理员有权限，然后才消耗高风险限流次数。AOP 顺序要通过 `@Order` 明确并用测试锁定，不能依赖默认顺序。

## 第 14 节：登录两段式限流

登录不能只按 IP 严格限制，因为校园网和运营商 NAT 下很多人可能共享 IP。

```text
调用微信接口前：宽松的IP摘要 + 全局洪峰
    ↓
code换到openid
    ↓
创建登录态前：严格的openid HMAC摘要
```

### 必须先做的小重构

当前 `UserServiceImpl.weChatLogin()` 把“换 openid”和“创建用户”放在一个方法里。先拆成两个内部步骤：

```java
String resolveOpenid(UserLoginDTO userLoginDTO);

User loginOrCreateByOpenid(
        String openid,
        String nickName
);
```

Controller 顺序改为：

```java
loginRateLimitService.checkIpPreflight(request);

String openid = userService.resolveOpenid(userLoginDTO);

loginRateLimitService.checkOpenid(openid);

User user = userService.loginOrCreateByOpenid(
        openid,
        userLoginDTO.getNickName()
);
```

`checkOpenid()` 的 Redis subject 必须是：

```java
HmacSHA256(serverSecret, openid)
```

不能使用：

```java
openid                 // 泄露稳定标识
sha256(openid)         // 没有服务端密钥，可能被离线枚举
userLoginDTO.getCode() // 一次性凭证，不稳定且禁止记录
```

配置写入 `application.yml`，数值不要硬编码在业务类中：

```yaml
quanta:
  security:
    rate-limit:
      login-ip-per-minute: 60
      login-global-per-minute: 1000
      login-openid-per-five-minutes: 10
      content-publish-per-minute: 5
      answer-comment-per-minute: 15
      report-per-ten-minutes: 10
      upload-per-minute: 20
      event-replay-per-five-minutes: 3
      security-management-per-five-minutes: 10
      hmac-secret: ${RATE_LIMIT_HMAC_SECRET}
```

### 阶段 5 验收（由 Codex 执行）

- Lua 并发计数准确，第一次请求一定设置 TTL。
- 第 6 次发帖返回 HTTP 429、`code=429` 和 `Retry-After`。
- 不同 userId 互不影响。
- 事件重放超过阈值时业务方法不执行，也不写 SUCCESS 审计。
- Redis 异常时高风险管理操作拒绝；明确配置为 fail-open 的匿名查询才允许放行。
- Redis Key 和日志中不存在 Token、微信 code、openid 明文或完整 IP。

---

# 五、阶段 6：后端安全上下文接口

## 第 15 节：让前端从后端恢复真实权限

### 以前为什么不行

管理端现在把 `isAdmin` 放在 `localStorage`。浏览器里的值可以被用户随手修改，也无法反映管理员刚刚被降权。

前端保存权限只能用于控制菜单显示，真正权限必须从后端读取。

### 1. 新建 VO

文件：`src/main/java/com/quanta/demo0/vo/SecurityContextVO.java`

```java
package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前登录用户安全上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityContextVO {

    private Long userId;
    private Set<String> roles;
    private Set<String> authorities;
    private Boolean verified;
}
```

### 2. 在 UserController 增加接口

增加 import：

```java
import com.quanta.demo0.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
```

增加方法：

```java
/**
 * 查询当前用户由后端确认的角色和权限。
 */
@PreAuthorize("isAuthenticated()")
@GetMapping("/security-context")
public Result<SecurityContextVO> getSecurityContext(
        Authentication authentication
) {
    AuthenticatedUser currentUser =
            (AuthenticatedUser) authentication.getPrincipal();

    SecurityContextVO securityContextVO =
            SecurityContextVO.builder()
                    .userId(currentUser.getUserId())
                    .roles(currentUser.getRoles())
                    .authorities(currentUser.getAuthorities())
                    .verified(currentUser.getVerified())
                    .build();

    return Result.success(securityContextVO);
}
```

不要让前端把角色作为参数传回来，也不要从 JWT 的旧 `isAdmin` claim 构造权限。

---

# 六、阶段 6：管理端权限改造

管理端目录：`../demo0-admin`

## 第 16 节：Store 不再以 isAdmin 为权限事实

文件：`demo0-admin/src/stores/user.js`

增加：

```javascript
const roles = ref([])
const authorities = ref([])
const verified = ref(false)
const securityLoaded = ref(false)

function setSecurityContext(payload = {}) {
  roles.value = Array.isArray(payload.roles) ? payload.roles : []
  authorities.value = Array.isArray(payload.authorities)
    ? payload.authorities
    : []
  verified.value = Boolean(payload.verified)
  securityLoaded.value = true
}

function hasAuthority(authority) {
  return authorities.value.includes(authority)
}

function hasAnyManagementRole() {
  return roles.value.some((role) => [
    'CONTENT_AUDITOR',
    'OPERATIONS_ADMIN',
    'SUPER_ADMIN',
  ].includes(role))
}
```

`clearSession()` 同时清空这些状态：

```javascript
roles.value = []
authorities.value = []
verified.value = false
securityLoaded.value = false
```

最终 return 增加这些字段和方法。`isAdmin` 可以在兼容窗口保留，但不能再参与路由授权判断。

## 第 17 节：登录后读取 security-context

文件：`demo0-admin/src/api/auth.js`

增加：

```javascript
getSecurityContext: () => request.get('/user/security-context'),
```

登录页不再调用 `verifyAdminAccess()` 猜测是不是管理员，改为：

```javascript
const securityContext = await authApi.getSecurityContext()
userStore.setSecurityContext(securityContext)

if (!userStore.hasAnyManagementRole()) {
  userStore.clearSession()
  error.value = '当前账号没有管理端权限'
  return
}
```

页面刷新时如果有 Token 但 `securityLoaded=false`，先请求一次安全上下文，再决定路由。不能只读 localStorage 中的角色。

## 第 18 节：路由使用 authority

文件：`demo0-admin/src/router/index.js`

路由增加：

```javascript
meta: {
  title: '用户管理',
  authority: 'USER_READ_ADMIN',
}
```

对应关系：

| 路由 | authority |
| --- | --- |
| 用户管理 | `USER_READ_ADMIN` |
| 帖子、回答、评论、举报 | `CONTENT_READ_ADMIN` |
| 身份认证 | `IDENTITY_AUDIT` |
| 事件中心 | `EVENT_READ` |
| 审计日志 | `AUDIT_LOG_READ` |
| 角色管理 | `ROLE_MANAGE` |

路由守卫核心判断：

```javascript
const requiredAuthority = to.meta.authority

if (requiredAuthority
    && !userStore.hasAuthority(requiredAuthority)) {
  return { name: 'Forbidden' }
}
```

新增无权限页面路由：

```javascript
{
  path: '/403',
  name: 'Forbidden',
  component: () => import('../views/error/Forbidden.vue'),
}
```

403 不能跳登录页，因为用户仍然登录，只是没有当前权限。

## 第 19 节：菜单和按钮权限

文件：`demo0-admin/src/layouts/DefaultLayout.vue`

例如用户管理菜单：

```vue
<el-menu-item
  v-if="userStore.hasAuthority('USER_READ_ADMIN')"
  index="/user"
>
  <el-icon :size="18"><User /></el-icon>
  <span>用户管理</span>
</el-menu-item>
```

删除按钮：

```vue
<el-button
  v-if="userStore.hasAuthority('CONTENT_DELETE')"
  type="danger"
  link
  @click="handleDelete(row)"
>
  删除
</el-button>
```

按钮对应关系：

```text
审核按钮 -> CONTENT_AUDIT
删除按钮 -> CONTENT_DELETE
封禁按钮 -> USER_BAN
事件重放 -> EVENT_REPLAY
角色修改 -> ROLE_MANAGE
```

隐藏按钮只改善体验，后端 `@PreAuthorize` 仍然是最终防线。

## 第 20 节：管理端区分 401、403、429

文件：`demo0-admin/src/api/request.js`

在业务 `payload.code` 判断和 HTTP error 判断中都增加 429：

```javascript
if (payload.code === 429) {
  if (!response.config.skipGlobalError) {
    ElMessage.warning(payload.msg || '操作过于频繁，请稍后再试')
  }
  return Promise.reject(new Error(payload.msg || '429'))
}
```

```javascript
if (status === 429) {
  if (!error.config?.skipGlobalError) {
    ElMessage.warning(
      data?.msg || '操作过于频繁，请稍后再试',
    )
  }
  return Promise.reject(error)
}
```

最终语义必须是：

- 401：清 Token，跳登录。
- 403：不清 Token，提示无权限。
- 429：不清 Token，提示稍后重试。

## 第 21 节：增加审计日志页面

新增：

```text
demo0-admin/src/api/auditLog.js
demo0-admin/src/views/audit/AuditLogList.vue
```

API：

```javascript
import request from './request'

export const auditLogApi = {
  page: (params) =>
    request.get('/admin/audit-logs/page', { params }),
  detail: (id) =>
    request.get(`/admin/audit-logs/${id}`),
}
```

页面沿用现有列表风格，至少提供：

- 时间范围、操作人、动作、目标类型、结果筛选。
- 操作人、动作、目标、结果、时间表格列。
- 抽屉展示前后摘要、requestId、客户端信息和失败原因。
- 不提供删除按钮。

后端负责脱敏，前端不要假设拿到的数据一定安全。

---

# 七、阶段 6：小程序请求层改造

小程序目录：`../demo0-miniprogram`

## 第 22 节：扩展错误类型

文件：`demo0-miniprogram/miniprogram/types/api.ts`

在 `ApiErrorType` 中增加：

```typescript
| 'forbidden'
| 'rateLimited'
```

## 第 23 节：正确处理 HTTP 403 和 429

文件：`demo0-miniprogram/miniprogram/utils/request.ts`

先提取标准错误消息：

```typescript
function readApiMessage(body: unknown, fallback: string): string {
  if (body && typeof body === 'object' && 'msg' in body) {
    const msg = (body as { msg?: unknown }).msg;
    if (typeof msg === 'string' && msg.trim()) {
      return msg;
    }
  }
  return fallback;
}
```

在通用非 2xx 判断前增加：

```typescript
if (status === 403) {
  const msg = readApiMessage(res.data, '没有权限执行该操作');
  showToastIfNeeded(showErrorToast, msg);
  resolve({
    ok: false,
    errorType: 'forbidden',
    message: msg,
    statusCode: status,
  });
  return;
}

if (status === 429) {
  const msg = readApiMessage(res.data, '操作过于频繁，请稍后再试');
  showToastIfNeeded(showErrorToast, msg);
  resolve({
    ok: false,
    errorType: 'rateLimited',
    message: msg,
    statusCode: status,
  });
  return;
}
```

在 `result.code !== 200` 前增加：

```typescript
if (code === 403) {
  const msg = result.msg || '没有权限执行该操作';
  showToastIfNeeded(showErrorToast, msg);
  resolve({
    ok: false,
    errorType: 'forbidden',
    message: msg,
    statusCode: status,
  });
  return;
}

if (code === 429) {
  const msg = result.msg || '操作过于频繁，请稍后再试';
  showToastIfNeeded(showErrorToast, msg);
  resolve({
    ok: false,
    errorType: 'rateLimited',
    message: msg,
    statusCode: status,
  });
  return;
}
```

只有 401 可以执行 `clearToken()` 和 `navigateToLoginPage()`。403、429 都不能清理登录态。

修改 TypeScript 后使用项目已有构建命令生成 JavaScript 和 `dist`，不要把手工修改 `request.js` 当成源代码修复。

---

# 八、阶段 7：旧安全逻辑收口

## 第 24 节：删除旧 JwtTokenUserInterceptor

当前 HTTP 已由 `OptionalJwtAuthenticationFilter` 处理，并且旧拦截器已经不再注册。完成全量回归后删除：

```text
src/main/java/com/quanta/demo0/interceptor/JwtTokenUserInterceptor.java
```

删除前使用：

```powershell
rg -n "JwtTokenUserInterceptor" src
```

只有类本身存在引用时才删除。

## 第 25 节：停止把 isAdmin 写入 JWT

文件：`src/main/java/com/quanta/demo0/controller/user/UserController.java`

删除：

```java
claims.put(
        JwtClaimsConstant.IS_ADMIN,
        user.getIsAdmin() != null ? user.getIsAdmin() : 0
);
```

再确认全项目没有读取该 claim 后，从 `JwtClaimsConstant` 删除：

```java
public static final String IS_ADMIN = "isAdmin";
```

`tb_user.is_admin`、`User.isAdmin` 和旧管理列表筛选暂时保留兼容，不在这个版本直接删数据库字段。权限事实源已经是 `user_role`。

## 第 26 节：清理重复 CORS

文件：`src/main/java/com/quanta/demo0/config/WebMvcConfiguration.java`

如果还存在 `addCorsMappings()`，删除该方法。HTTP CORS 只保留 `SecurityConfiguration.corsConfigurationSource()`；WebSocket 来源白名单继续由 `WebSocketConfig` 使用同一份 `SecurityProperties`。

## 第 27 节：按环境开放 Swagger

增加配置：

```yaml
quanta:
  security:
    api-docs-enabled: ${API_DOCS_ENABLED:false}
```

开发配置显式设为 `true`，生产保持 `false`。`SecurityConfiguration` 不能永远无条件 `permitAll` 文档路径；根据配置决定公开或拒绝，并补开发、生产两个配置测试。

更简单的第一版也可以使用 SpringDoc 官方开关：

```yaml
springdoc:
  api-docs:
    enabled: ${API_DOCS_ENABLED:false}
  swagger-ui:
    enabled: ${API_DOCS_ENABLED:false}
```

但安全规则仍需同步，避免页面关闭后接口路径意外开放。

## 第 28 节：更新文档

只记录真正完成并通过验证的内容：

- 角色与权限矩阵。
- 401、403、429 的区别。
- HTTP 与 WebSocket 认证链路。
- 审计日志事务策略。
- Redis Lua 限流维度和异常策略。
- 管理员降权为什么会撤销 Redis 登录态。
- 哪些 Service 禁止添加 `@PreAuthorize`。

不要在项目说明中声称“封禁立即主动关闭旧 WebSocket”，当前第一版只是阻止后续实时推送。主动关闭旧连接属于后续增强。

---

# 九、最终测试与验收顺序

测试代码继续由 Codex 编写和执行，教学时你只需要理解验收目标。

## 检查点 A：阶段 3 收尾

- 正常用户收到实时通知。
- 封禁用户仍保存数据库通知，但不发送 WebSocket。
- MQ 始终正常 ACK，不因为跳过推送重试。

## 检查点 B：审计

- 审核、删除、封禁、解封、事件重放、角色变更均有记录。
- 成功记录与业务一起提交。
- 业务回滚时不存在错误 SUCCESS。
- 失败记录使用独立事务保存。
- 审计日志不包含 Token、微信 code、证件号和正文。

## 检查点 C：限流

- 并发 Lua 计数准确且 Key 有 TTL。
- 超限同时返回 HTTP 429、`Result.code=429`、`Retry-After`。
- 401、403、429 不混淆。
- 高风险操作限流 Redis 异常时 fail-closed。

## 检查点 D：角色与降权

- 内容审核员不能封禁用户、重放事件或修改角色。
- 运营管理员不能删除内容或重放事件。
- 只有 `SUPER_ADMIN` 拥有 `ROLE_MANAGE`、`EVENT_REPLAY`、`AUDIT_LOG_READ`。
- 撤销管理角色后 Redis 登录态被删除，旧 Token 立即 401。
- 角色修改不会影响 MQ 和定时任务。

## 检查点 E：前端

- 管理端菜单、路由和按钮按 authority 显示。
- 直接手工调用无权 API 仍返回 403。
- 401 清会话，403 和 429 保留会话。
- 小程序 TypeScript 构建成功并重新生成 JavaScript、`dist`。
- 管理端生产构建成功并完成真实浏览器验证。

## 检查点 F：全链路

建议最后按顺序执行：

```text
1. mvn -DskipTests compile
2. 安全专项单元测试
3. MQ可靠性测试
4. 全部后端测试
5. MySQL、Redis、RabbitMQ真实依赖测试
6. 管理端生产构建
7. 小程序构建
8. 管理端真实浏览器验证
9. 小程序开发者工具或真机验证
```

如果本地 Redis、RabbitMQ、Elasticsearch 没启动，要把“环境依赖不可用”和“代码测试失败”分开报告，不能混为一谈。

---

# 十、完成后的面试表达

可以这样通俗说明：

> 项目不是把所有安全问题都塞进一个过滤器，而是分层处理。Filter 负责确认用户身份，Controller 的 `@PreAuthorize` 负责权限，业务 Service 负责资源归属，MQ 内部任务不依赖当前登录用户。管理员高风险操作会写入事务审计，Redis Lua 负责原子限流。HTTP 和 WebSocket 共用认证事实，但因为传输链路不同，各自在入口适配。这样既避免重复鉴权，也不会让 Spring Security 打断 MQ 和定时任务。

最重要的三个设计理由：

1. `hasRole` 表示身份分组，`hasAuthority` 表示具体能力。
2. 前端隐藏按钮只是体验，后端权限才是安全边界。
3. 数据库通知是事实，WebSocket 只是实时体验，所以封禁时跳过推送但仍保存通知并 ACK。
