# LoggingX Java

LoggingX 是一个基于 Spring Boot 4.1、Spring Security 7 和 Java 25 的智能日志分析平台后端。

## 模块结构

```text
loggingx-java
├── loggingx-common       # 通用响应模型与应用异常
├── loggingx-dao          # 数据访问接口及 MySQL（MyBatis）实现
├── loggingx-thirdparty   # 外部系统适配器
├── loggingx-auth         # 注册、登录、JWT 与安全配置
├── loggingx-biz          # 用户及日志分析业务服务
└── loggingx-web          # Spring Boot 启动入口和 HTTP API
```

模块依赖方向保持单向：`web -> (auth, biz, thirdparty) -> dao -> common`。

## 启动

需要 Java 25 和 Maven 3.6.3 或更高版本：

本地开发使用 Spring Boot 内嵌的 Tomcat 和 `dev` profile，它提供本地 JWT 密钥并写入演示数据，服务地址为 `http://localhost:8080`：

```shell
mvn spring-boot:run -pl loggingx-web -am -Dspring-boot.run.profiles=dev
```

### 部署到外部 Tomcat

`loggingx-web` 打包为 WAR，Tomcat 相关依赖为 `provided`，不会与容器自带的 Tomcat 冲突：

```shell
mvn clean package
```

产物为 `loggingx-web/target/loggingx.war`。要求：

- **Tomcat 11**（Spring Boot 4 基于 Servlet 6.1 / Jakarta EE 11，Tomcat 10 及以下不可用），运行 Tomcat 的 JVM 为 **Java 25**。
- 复制到 `$CATALINA_HOME/webapps/` 后，访问路径为 `/loggingx`，例如 `http://host:8080/loggingx/api/auth/login`。若要部署在根路径，改名为 `ROOT.war`。
- 不激活 `dev` profile，因此必须提供 JWT 密钥（至少 32 字节），否则应用启动失败；首次部署时一并设置超级管理员密码（见下文）。可写在 `$CATALINA_HOME/bin/setenv.sh`（Windows 为 `setenv.bat`，用 `set NAME=value`）：

```shell
export LOGGINGX_SECURITY_JWT_SECRET=your-long-random-secret
export LOGGINGX_BOOTSTRAP_SUPER_ADMIN_PASSWORD=your-password
```

日志输出到 Tomcat 的控制台日志（`logs/catalina.out` 或 `catalina.*.log`）。打包出的 WAR 也可以直接 `java -jar loggingx.war` 用内嵌 Tomcat 运行。

### 数据库

用户、角色、菜单数据保存在 MySQL（`sys_user`、`sys_role`、`sys_menu` 及关联表 `sys_user_role`、`sys_role_menu`）。连接信息在 `application.yml` 的 `spring.datasource`，可用环境变量 `LOGGINGX_DATASOURCE_URL`、`LOGGINGX_DATASOURCE_USERNAME`、`LOGGINGX_DATASOURCE_PASSWORD` 覆盖。表结构见 `loggingx-dao/src/main/resources/db/schema.sql`，每次启动时执行，只创建缺失的表；修改已有表需要手工迁移。

数据访问使用 MyBatis：每个仓储（如 `MybatisUserAccountRepository`）调用对应的 `*Mapper` 接口，SQL 在 `loggingx-dao/src/main/resources/mapper/*.xml`。连接池为 HikariCP（`spring.datasource.hikari`，默认固定 10 个连接，可用 `LOGGINGX_DATASOURCE_POOL_SIZE` 调整）。时间字段一律按 UTC 存入 `DATETIME(6)`（`UtcInstantTypeHandler`）。

仓储的 `update(expected, updated)` 是比较并交换（CAS）语义：在事务中 `SELECT ... FOR UPDATE` 锁定该行，与 `expected` 比较一致后才写入；唯一约束由数据库保证。测试使用 H2 的 MySQL 兼容模式（`loggingx-web/src/test/resources/config/application.yml`），不会连接 MySQL。

任何环境启动时，若仓储为空，都会写入与前端路由一致的菜单和 R_SUPER / R_ADMIN / R_USER 三个角色（`SystemDataInitializer`）。若没有任何账号持有 R_SUPER，会按下列环境变量创建第一个超级管理员；未设置密码时只打印警告。首次登录后请修改其密码：

```shell
LOGGINGX_BOOTSTRAP_SUPER_ADMIN_USERNAME=super          # 可选，默认 super
LOGGINGX_BOOTSTRAP_SUPER_ADMIN_PASSWORD=your-password  # 需满足密码规则
```

`dev` profile 下另外写入三个演示账号（密码均为 `123456`，仅用于演示，不受下文密码规则约束）：

| 账号 | 角色 | 可访问菜单 |
| --- | --- | --- |
| super | R_SUPER 超级管理员 | 全部（该角色始终拥有全部菜单，不可删除或禁用） |
| admin | R_ADMIN 管理员 | 仪表盘、用户管理、个人中心、异常页 |
| user | R_USER 普通用户 | 仪表盘、异常页 |

### Redis

连接信息在 `application.yml` 的 `spring.data.redis`，可用 `LOGGINGX_REDIS_HOST`、`LOGGINGX_REDIS_PORT`、`LOGGINGX_REDIS_PASSWORD`、`LOGGINGX_REDIS_DATABASE` 覆盖。客户端为 Lettuce，所有线程共享一个线程安全的连接，不需要连接池。键统一以 `loggingx:` 开头。用途：

- 登录失败锁定（`LoginAttemptLimiter`）。
- 缓存角色表和菜单表（`CachingRoleRepository`、`CachingMenuRepository`）：每张表整体缓存为一个 JSON 值（`loggingx:cache:v1:roles` / `menus`），任何写操作后立即清除，有效期 `loggingx.cache.ttl`（默认 10 分钟）。写操作仍直接走数据库并加行锁。Redis 不可用时读操作回落到数据库。缓存的数据结构变化时，把键里的 `v1` 加一。测试使用进程内的 jedis-mock，不会连接 Redis。

## 接口约定

- 响应统一为 `{"code": 200, "msg": "OK", "data": ...}`；失败时 `code` 为 HTTP 状态码，`msg` 为原因。
- 除 `/api/auth/**` 外均需携带 `Authorization: Bearer <token>`。token 绑定用户 ID；修改密码或停用账号后，该用户已签发的 token 立即失效。
- 认证失败（未登录、token 失效）返回 401，无权限返回 403，响应体同样是上述结构。
- 用户名去除首尾空格后为 2–50 位、不含空白字符，统一转小写（`UsernamePolicy`）。
- 密码长度 8–100 位（`PasswordPolicy`），注册、新增用户、修改密码统一校验。
- 同一用户名 15 分钟内连续登录失败 5 次后锁定 15 分钟，期间登录返回 429（`LoginAttemptLimiter`，状态存于 Redis，多实例共享；Redis 不可用时放行登录并记录告警）。
- 管理接口按"角色被分配的菜单"授权：能访问用户管理菜单（User）才能调用用户接口，角色管理（Role）、菜单管理（Menus）同理。
- 非超级管理员只能授予、管理自己已拥有的权限：分配的角色或菜单，以及被修改的用户或角色当前拥有的权限，都必须是操作人权限的子集，否则返回 403。
- 编辑时若记录已被他人修改，返回 409，需刷新后重试。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，body: `{"userName","password"}`，返回 `{"token","tokenType","expiresIn"}` |
| POST | `/api/auth/register` | 注册，授予 R_USER 角色 |
| GET | `/api/user/info` | 当前用户信息、角色编码、按钮权限 |
| GET | `/api/user/list` | 用户分页，参数 `current,size,userName,userGender,userPhone,userEmail,status` |
| POST / PUT / DELETE | `/api/user`、`/api/user/{id}` | 新增 / 编辑 / 删除用户 |
| GET | `/api/role/list` | 角色分页，参数 `current,size,roleName,roleCode,description,enabled,startTime,endTime` |
| GET | `/api/role/options` | 启用的角色（下拉选项） |
| POST / PUT / DELETE | `/api/role`、`/api/role/{id}` | 新增 / 编辑 / 删除角色 |
| GET / PUT | `/api/role/{id}/menus` | 查询 / 保存角色的菜单和按钮 ID |
| GET | `/api/menu/routes` | 当前用户可访问的菜单（前端后端权限模式下生成路由） |
| GET | `/api/menu/tree` | 完整菜单树（含禁用菜单和全部按钮） |
| POST / PUT / DELETE | `/api/menu`、`/api/menu/{id}` | 新增 / 编辑 / 删除菜单或按钮（删除菜单会连同子菜单和按钮） |

健康检查：`GET /actuator/health`
