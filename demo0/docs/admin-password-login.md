# 管理端账号密码初始化

管理端账号密码登录使用独立的 `admin_credential` 表。凭据只绑定已有用户，不创建用户、不授予角色，也不会覆盖已有凭据。第一次初始化由操作人员在本机终端输入密码，密码不会出现在命令行参数、仓库文件或程序输出中。

## 前置条件

1. 已确认目标用户存在于 `tb_user`，且 `account_status = 0`、`is_deleted = 0`。
2. 该用户已经在 `user_role` 中拥有至少一个管理角色：`SUPER_ADMIN`、`OPERATIONS_ADMIN` 或 `CONTENT_AUDITOR`。初始化工具不会替用户补角色。
3. 已准备 JDK 17+、Maven、PowerShell 和目标 MySQL。`docker info` 只用于测试，不是生产初始化的替代品。
4. 由部署系统或本地密钥管理器把 `DB_PASSWORD` 注入进程环境。数据库密码只放环境变量，不能写进命令参数。若必须在临时 PowerShell 会话中手动输入，使用安全输入并在调用脚本后清除：

   ```powershell
   $secureDbPassword = Read-Host 'MySQL 密码' -AsSecureString
   $dbPasswordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureDbPassword)
   try {
     $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($dbPasswordPtr)
     .\scripts\init-admin-credential.ps1
   } finally {
     [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbPasswordPtr)
     $env:DB_PASSWORD = $null
   }
   ```

   这段代码只在当前进程内短暂保留数据库密码，不把它放入命令行参数。不要把真实值写入 `.env`、脚本、日志或 Git。

## 手动执行数据库迁移

先在目标数据库执行建表迁移。`-p` 会让 MySQL 客户端在终端提示数据库密码，不要把密码拼接到命令行：

```powershell
Set-Location C:\path\to\QuantaCommunity\demo0
mysql -h 127.0.0.1 -P 3306 -u root -p demo `
  --execute="source src/main/resources/db/V_admin_credentials.sql"
```

这里使用 MySQL 客户端的 `source` 命令，避免 PowerShell 不支持 Unix shell `<` 输入重定向；先进入 `demo0` 目录也能避免路径中包含空格时的转义问题。

迁移使用 `CREATE TABLE IF NOT EXISTS`，重复执行不会创建第二张表。迁移只建立结构，不插入默认账号、默认密码或任何角色。执行后可以只查看非敏感元数据：

```sql
SELECT user_id, username, enabled
FROM admin_credential
ORDER BY user_id;
```

不要查询、复制或打印 `password_hash`。初始化前可用下面的只读查询确认用户和角色，查询结果不包含密码：

```sql
SELECT u.id, u.account_status, u.is_deleted, r.role_code
FROM tb_user u
JOIN user_role r ON r.user_id = u.id
WHERE u.id = <已有用户ID>
  AND r.role_code IN ('SUPER_ADMIN', 'OPERATIONS_ADMIN', 'CONTENT_AUDITOR');
```

## 初始化一条凭据

在 `demo0` 目录执行：

```powershell
Set-Location C:\path\to\QuantaCommunity\demo0
.\scripts\init-admin-credential.ps1
```

脚本会先编译当前主服务并准备运行时依赖，然后启动一次性初始化工具。工具依次要求输入已有用户 ID、账号、密码和密码确认；也可以只把非敏感的用户 ID、账号作为参数传入：

```powershell
.\scripts\init-admin-credential.ps1 -UserId 123 -Username admin.ops
```

绝对不要增加 `-Password` 参数，也不要在 PowerShell 历史中粘贴密码。账号规则与 `AdminPasswordLoginDTO` 一致：规范化为小写，长度 3～64 位，首字符为字母或数字，其余只能是字母、数字、点、下划线或短横线。密码不能为空，UTF-8 字节数不能超过 72；密码由 BCrypt 12 轮哈希后写入。

工具在同一事务内依次校验：

- 用户存在、未被封禁且未软删除；
- 用户已有至少一个受支持的管理角色；
- `user_id` 和规范化账号都没有已有凭据。

任一条件不满足，事务回滚并停止。已有凭据不会更新，角色表也不会被修改。成功输出只确认初始化完成，不输出账号密码、哈希或数据库连接信息。

脚本读取的数据库连接环境变量按以下优先级生效：

| 用途 | 优先级 | 默认值 |
|---|---|---|
| JDBC URL | `ADMIN_DB_URL` → `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3306/demo?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC` |
| 数据库账号 | `ADMIN_DB_USERNAME` → `SPRING_DATASOURCE_USERNAME` | `root` |
| 数据库密码 | `DB_PASSWORD` | 必须显式设置 |

脚本不会把 `DB_PASSWORD` 拼接到 Java 或 Maven 命令行中。若 Maven 编译失败或依赖 classpath 准备失败，脚本不会执行凭据写入。

## 启用登录与验证

应用使用已有的 `DB_PASSWORD` 启动，管理端登录入口为 `POST /admin/auth/login`。登录页面提交账号和密码即可；前端不持久化密码。服务端登录成功后仍从数据库加载真实角色，并复用已有 JWT/Redis 会话。

`quanta.security.dev-login-enabled` 默认是 `false`，正式环境保持关闭。只有本地联调确实需要旧的测试 code 入口时，才在临时开发环境显式设置 `DEV_LOGIN_ENABLED=true`；这不替代账号密码，也不应带入生产配置。

验证时检查以下结果：

1. 正确账号密码可以进入管理端，并能按服务端角色看到对应菜单和权限。
2. 错误密码、被封禁用户、已软删除用户、没有管理角色的用户均不能取得 token。
3. 重复运行初始化工具不会改变原有 `username` 或 `password_hash`。
4. 修改或撤销管理角色后，服务端会清理该用户会话；页面需要重新登录。

## 会话限制与回滚

当前项目沿用同一个用户一份 Redis 登录态。管理端和小程序使用同一用户登录时，后一次登录会替换前一次 token；这属于现有单会话限制。若需要两端同时在线，应另行设计带客户端维度的会话 key 和 JWT audience，不在初始化工具中规避。

停用凭据时先禁用登录入口或回滚应用部署，再保留数据执行：

```sql
UPDATE admin_credential
SET enabled = 0
WHERE user_id = <目标用户ID>;
```

该操作不删除原用户、角色或审计数据。若需要撤销整张凭据表迁移，先确认新登录入口已完全停用，并按数据库备份和发布回滚流程处理；不要通过删除用户或角色来回滚密码登录。
