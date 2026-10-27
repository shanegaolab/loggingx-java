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
├── loggingx-ai           # 基于 Spring AI 的大模型对话（OpenAI、Claude、DeepSeek）
└── loggingx-web          # Spring Boot 启动入口和 HTTP API
```

模块依赖方向保持单向：`web -> (auth, biz, thirdparty, ai) -> dao -> common`，`ai` 只依赖 `common`。

## 启动

需要 Java 25 和 Maven 3.6.3 或更高版本：

本地开发使用 Spring Boot 内嵌的 Tomcat 和 `dev` profile，它提供本地 JWT 密钥并写入演示数据，服务地址为 `http://localhost:8080`：

```shell
mvn spring-boot:run -pl loggingx-web -am -Dspring-boot.run.profiles=dev
```

不连接 MySQL、Redis、ClickHouse 也能本地跑起来：`TestLoggingxApplication` 使用测试环境的配置（内存 H2、进程内 Redis、内存日志存储，见 `loggingx-web/src/test/resources/config`），数据在退出后丢失，适合前后端联调：

```shell
mvn -pl loggingx-web -am install -DskipTests
mvn -pl loggingx-web spring-boot:test-run -Dspring-boot.run.profiles=dev
```

### 环境变量

可覆盖的配置均以 `LOGGINGX_` 开头，完整列表及默认值见 [`.env.example`](.env.example)。除系统环境变量外，也可把它复制为启动工作目录下的 `.env`（已被 git 忽略），取消注释并填写需要的项；同名系统环境变量优先。`.env` 按 Java properties 格式读取：值不加引号，未注释的空值会覆盖默认值。

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
export LOGGINGX_JWT_SECRET=your-long-random-secret
export LOGGINGX_BOOTSTRAP_ADMIN_PASSWORD=your-password
```

日志输出到 Tomcat 的控制台日志（`logs/catalina.out` 或 `catalina.*.log`）。打包出的 WAR 也可以直接 `java -jar loggingx.war` 用内嵌 Tomcat 运行。

### 数据库

用户、角色、菜单、部门、岗位数据保存在 MySQL（`sys_user`、`sys_role`、`sys_menu`、`sys_dept`、`sys_post` 及关联表 `sys_user_role`、`sys_role_menu`）。连接信息在 `application.yml` 的 `spring.datasource`，可用环境变量 `LOGGINGX_DATASOURCE_URL`、`LOGGINGX_DATASOURCE_USERNAME`、`LOGGINGX_DATASOURCE_PASSWORD` 覆盖。表结构见 `loggingx-dao/src/main/resources/db/schema.sql`，每次启动时执行，只创建缺失的表；修改已有表需要手工迁移。

数据访问使用 MyBatis：每个仓储（如 `MybatisUserAccountRepository`）调用对应的 `*Mapper` 接口，SQL 在 `loggingx-dao/src/main/resources/mapper/*.xml`。连接池为 HikariCP（`spring.datasource.hikari`，默认固定 10 个连接，可用 `LOGGINGX_DATASOURCE_POOL_SIZE` 调整）。时间字段一律按 UTC 存入 `DATETIME(6)`（`UtcInstantTypeHandler`）。

仓储的 `update(expected, updated)` 是比较并交换（CAS）语义：在事务中 `SELECT ... FOR UPDATE` 锁定该行，与 `expected` 比较一致后才写入；唯一约束由数据库保证。测试使用 H2 的 MySQL 兼容模式（`loggingx-web/src/test/resources/config/application.yml`），不会连接 MySQL。

任何环境启动时，若仓储为空，都会写入与前端路由一致的菜单和 R_SUPER / R_ADMIN / R_USER 三个角色（`SystemDataInitializer`）。已有数据库启动时，若「系统管理」下缺少部门管理（`Dept`）或岗位管理（`Post`）菜单会自动补上，补上的菜单默认只有超级管理员可见，需要在角色管理中授权给其他角色。`dev` profile 下还会在部门、岗位为空时写入演示数据。若没有任何账号持有 R_SUPER，会按下列环境变量创建第一个超级管理员；未设置密码时只打印警告。首次登录后请修改其密码：

```shell
LOGGINGX_BOOTSTRAP_ADMIN_USERNAME=superuser    # 可选，默认 superuser
LOGGINGX_BOOTSTRAP_ADMIN_PASSWORD=your-password  # 需满足密码规则
```

`dev` profile 下另外写入三个演示账号（密码均为 `123456`，仅用于演示，不受下文密码规则约束）：

| 账号 | 角色 | 可访问菜单 |
| --- | --- | --- |
| super | R_SUPER 超级管理员 | 全部（该角色始终拥有全部菜单，不可删除或禁用） |
| admin | R_ADMIN 管理员 | 仪表盘、用户管理、部门管理、岗位管理、个人中心、异常页 |
| user | R_USER 普通用户 | 仪表盘、异常页 |

### Redis

连接信息在 `application.yml` 的 `spring.data.redis`，可用 `LOGGINGX_REDIS_HOST`、`LOGGINGX_REDIS_PORT`、`LOGGINGX_REDIS_PASSWORD`、`LOGGINGX_REDIS_DATABASE` 覆盖。客户端为 Lettuce，所有线程共享一个线程安全的连接，不需要连接池。键统一以 `loggingx:` 开头。用途：

- 登录失败锁定（`LoginAttemptLimiter`）。
- 缓存角色表和菜单表（`CachingRoleRepository`、`CachingMenuRepository`）：每张表整体缓存为一个 JSON 值（`loggingx:cache:v1:roles` / `menus`），任何写操作后立即清除，有效期 `loggingx.cache.ttl`（默认 10 分钟）。写操作仍直接走数据库并加行锁。Redis 不可用时读操作回落到数据库。缓存的数据结构变化时，把键里的 `v1` 加一。测试使用进程内的 jedis-mock，不会连接 Redis。

### 日志管理

日志库（`log_store`）保存在 MySQL；日志明细保存在 ClickHouse，由 `loggingx.log.storage` 选择存储：

| 取值 | 存储 | 用途 |
| --- | --- | --- |
| `clickhouse`（默认） | ClickHouse 的 `log_record` 表（`ClickHouseLogRecordRepository`） | 生产 |
| `memory` | 进程内存，每个日志库最多保留 `loggingx.log.memory.capacity-per-store` 条，重启丢失 | 测试、没有 ClickHouse 时本地运行 |

ClickHouse 连接用 `LOGGINGX_CLICKHOUSE_URL`（默认 `jdbc:clickhouse://localhost:8123/loggingx`）、`LOGGINGX_CLICKHOUSE_USERNAME`、`LOGGINGX_CLICKHOUSE_PASSWORD` 配置。数据库需事先创建（`CREATE DATABASE loggingx`），表结构见 `loggingx-dao/src/main/resources/db/clickhouse-schema.sql`，启动时自动创建。ClickHouse 不可用时应用照常启动，只有日志相关接口报错，恢复后需重启以建表。表设计：

- `MergeTree`，按天分区，按 `(store_id, log_time, seq, id)` 排序，查询都按日志库和时间范围过滤。
- 每行带写入时日志库的保留天数，过期数据由 TTL 在合并时删除；每天 03:30 的 `LogRetentionService` 再按当前保留天数补删（保留天数被改小时），并清理已删除日志库残留的日志。删除日志库时立即删除其日志（ClickHouse 轻量删除）。
- 日志 ID 由应用按接收时间生成（`LogIds`，类似雪花算法），实时日志按 ID 跟随。
- 关键字查询为不区分大小写的子串匹配（`ILIKE`），不走索引，需配合日志库和时间范围；TraceId 有 bloom filter 索引。

用真实 ClickHouse 验证仓储的 SQL，可运行默认跳过的 `ClickHouseLogRecordRepositoryLiveTest`（写入随机日志库 ID，结束后删除）：

```shell
LOGGINGX_TEST_CLICKHOUSE_URL=jdbc:clickhouse://host:8123/loggingx LOGGINGX_TEST_CLICKHOUSE_PASSWORD=... mvn test -pl loggingx-dao -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ClickHouseLogRecordRepositoryLiveTest
```

#### 日志上报

采集服务器上的日志文件或容器日志，推荐在「日志管理 → 采集 Agent」页面一键部署 Vector Agent（Linux / Docker / Kubernetes / Windows），采集内容由日志库的采集配置决定；安装后的 Agent 登记到日志库、定期同步，配置修改和 Token 重置会自动下发，日志库管理中可看到每个日志库的 Agent 及在线状态。选型、生成的配置、联动和运维说明见 [docs/agent.md](docs/agent.md)。相关接口：
- `/api/agent/config`、`/api/agent/install.sh`、`/api/agent/install.ps1`、`/api/agent/kubernetes.yaml`：请求头带日志库的上报 Token，或以日志库管理员身份带 `storeId` 访问。
- `GET`/`PUT /api/agent/collect?storeId=`（采集配置）、`GET /api/agent/agents`、`DELETE /api/agent/agents/{id}`：日志库管理员访问。
- `GET`/`DELETE /api/agent/sync`：Agent 同步与注销，凭请求头 `X-Agent-Id`、`X-Agent-Secret` 访问（首次带 `X-Log-Token` 登记）。
- `/api/agent/download/<安装包>`：公开。

**日志备份**：开启归档的日志库，每天把超过 N 天的日志按天压缩为 `.ndjson.gz` 上传到 S3 兼容对象存储（MinIO / OSS / COS / S3），归档按保留天数自动清理；**先归档、再删除**：未归档成功的日期不会被保留清理删除。归档可下载或恢复回日志库查询，详见 [docs/backup.md](docs/backup.md)。相关接口（一级菜单「备份管理」）：`/api/backup/storage`（配置与测试连接）、`/api/backup/policies`、`/api/backup/archives`（含 `/{id}/restore`、`/{id}/download-link`）、`POST /api/backup/run`、`GET /api/backup/status`；`GET /api/backup/download/<签名>` 凭 10 分钟有效的签名下载。新建 / 编辑日志库时可带 `backup` 一起设置归档，`DELETE /api/logstore/{id}?deleteArchives=true` 同时删除归档。

**主机监控**：Agent 同时采集所在主机的 CPU、内存、磁盘、网络、负载、进程 Top 和容器资源（在采集配置中开关），「监控中心」提供主机列表、趋势图、阈值告警（规则与通知在一级菜单「告警中心」），并与日志查询互相跳转，详见 [docs/agent.md#主机监控](docs/agent.md#主机监控)。相关接口：
- `POST /api/metrics/ingest`：Agent 上报指标（Vector `host_metrics` 经 `metric_to_log` 后的 JSON，可 gzip），凭 `X-Log-Token`。
- `GET /api/monitor/hosts`、`/api/monitor/hosts/series`、`/filesystems`、`/processes`、`/containers`、`/api/monitor/alerts`：「主机监控」或「告警中心」菜单可访问。
- `/api/monitor/alert-rules`：查看同上；增删改需「告警中心」菜单。

**告警与通知**：「告警中心」（一级菜单）分为**日志告警**（时间窗口内匹配级别 / 关键字的日志条数超过阈值）和**监控告警**（主机资源阈值、Agent 离线），每条规则可选择**邮件**（SMTP）和/或**短信**（阿里云或腾讯云，页面中选择）通知系统用户与通知组，支持恢复通知、重复提醒、测试发送和通知记录。SMTP 密码与短信密钥加密存库。详见 [docs/alerting.md](docs/alerting.md)；接口为 `/api/alert-center/*`（需「告警中心」菜单）。

两种方式，消息格式相同（`LogPayloads`）：请求体为日志数组、`{"logstore": "<编码>", "logs": [...]}` 或单条日志。单条日志是扁平 JSON 对象：

| 字段 | 说明 |
| --- | --- |
| `message` / `msg` / `log` | 日志内容，必填，为空的被丢弃；超过 16000 字符截断 |
| `time` / `timestamp` / `@timestamp` | ISO-8601、`yyyy-MM-dd HH:mm:ss[.SSS]`（服务器时区）或秒/毫秒时间戳；缺省或无法解析时为接收时间 |
| `level` / `severity` | TRACE、DEBUG、INFO、WARN、ERROR、FATAL，接受 warning、err、critical 等写法；缺省或无法识别时为 INFO |
| `host` / `hostname`、`source` / `logger` / `file`、`traceId` / `trace_id` | 主机、来源、链路 ID（Filebeat 的 `host.name`、`log.file.path` 也能识别） |
| `seq` / `sequence` / `offset` | 行在来源中的序号，如文件偏移（Filebeat 的 `log.offset` 也能识别），同一时间的日志按它排序，见下文「日志顺序」 |
| `fields` 及其他字段 | 作为附加字段（JSON）保存 |

- **HTTP**：`POST /api/logs/ingest`，请求头 `X-Log-Token` 为日志库的上报 Token（日志库管理页可复制、重置），不需要登录。请求体也可以是每行一个 JSON 的 NDJSON（如 Fluent Bit 的 `json_lines`）。每次最多 1000 条，返回 `{"accepted": n, "rejected": n}`。Token 错误返回 401，日志库停用返回 403。该接口不记录操作审计日志。
- **Kafka**：采集 Agent 把日志发到 Kafka，后端批量消费（`KafkaLogListener`）。设置 `LOGGINGX_KAFKA_ENABLED=true` 开启，`LOGGINGX_KAFKA_BOOTSTRAP_SERVERS`（默认 `localhost:9092`）、`LOGGINGX_KAFKA_TOPICS`（逗号分隔，默认 `loggingx-logs`）、`LOGGINGX_KAFKA_GROUP_ID`（默认 `loggingx`）配置连接；新消费组从最新消息开始（`LOGGINGX_KAFKA_AUTO_OFFSET_RESET`）。依次用消息体的 `logstore` 字段、`logstore` 消息头或消息 Key 指定日志库编码，不需要 Token。格式错误、日志库不存在或停用的消息记录告警后跳过，存储失败时整批重试。其余参数用 Spring Boot 标准属性 `spring.kafka.*`。

#### 日志顺序

查询和实时日志都按「日志时间 → 行序号 `seq` → 接收顺序（ID）」排序（`LogRecord.ORDER`）。同一时间戳下常有多行日志（异常堆栈、秒级精度的时间戳），它们的先后由后两者决定：

- **接收顺序**：同一请求、同一 Kafka 分区内的日志按发送顺序分配 ID，消费者按分区内偏移顺序处理，因此同一分区内的顺序不会被打乱。
- **跨分区**：Kafka 只保证分区内有序。同一来源的日志应发到同一分区：消息 Key 设为「主机:文件路径」之类的来源标识，日志库放在 `logstore` 消息头或消息体中（不要用日志库编码做 Key，否则一个日志库只能用一个分区）。
- **行序号**：采集端带上 `seq`（或 Filebeat 自带的 `log.offset`）后，即使同一来源的日志被分到不同分区、或重试后乱序到达，同一时间的日志仍按原始顺序排列。未提供时为 0，此时只能依赖接收顺序。
- **实时日志**按到达顺序追加；每批（约 1 秒）内按上述顺序排列，晚到的旧日志排在已显示的日志之后。需要严格按时间看时用日志查询。
- 已经建好 `log_record` 表的 ClickHouse 需要删表重建才会有 `seq` 列（表结构改了排序键）。

#### 查询与实时日志

- `GET /api/logs/search?storeId=&startTime=&endTime=&levels=ERROR,WARN&keyword=&host=&traceId=&current=&size=`：按日志时间倒序分页，`levelCounts` 为各级别命中数（不受级别筛选影响）。最多翻到前 10000 条，再往后需缩小时间范围。日志 `id` 以字符串返回（超出 JS 安全整数）。
- `GET /api/logs/tail?storeId=&levels=&keyword=`：`text/event-stream`，先推送最近 100 条，随后每秒推送新日志。`logs` 事件为日志数组，`ready` 表示最近日志已发送完，无新日志时每 15 秒发送注释心跳；连接 30 分钟后由服务端关闭，前端自动重连。按 ID 轮询存储，多实例部署、Kafka 由其他节点消费都能看到；只推送 2 秒前接收的日志（`loggingx.log.tail.settle-delay`），给其他节点进行中的写入留出时间。经 Nginx 时同样需要 `proxy_buffering off;`。
- 权限按菜单：日志库管理（`LogStore`，含 Token）、日志查询（`LogQuery`）、实时日志（`LogTail`）；日志库下拉选项 `/api/logstore/options` 不含 Token，三者任一即可访问。新库中管理员拥有全部日志菜单，普通用户拥有查询和实时日志；已有数据库启动时自动补上这些菜单，默认只有超级管理员可见。`dev` profile 下没有日志库时创建演示日志库 `demo-app`，日志库为空时写入 60 条示例日志。

### AI 大模型

`loggingx-ai` 基于 Spring AI 2.0 接入 OpenAI、Anthropic Claude 和 DeepSeek。设置了 API Key 的提供方才会启用（`AiConfiguration`），一个都不设也能正常启动，此时 `/api/ai/providers` 返回空列表：

| 提供方 | API Key | 默认模型（`_MODEL` 覆盖） | 默认地址（`_BASE_URL` 覆盖） |
| --- | --- | --- | --- |
| `openai` | `LOGGINGX_AI_OPENAI_API_KEY` | `gpt-5-mini` | `https://api.openai.com/v1` |
| `anthropic` | `LOGGINGX_AI_ANTHROPIC_API_KEY` | `claude-haiku-4-5` | `https://api.anthropic.com` |
| `deepseek` | `LOGGINGX_AI_DEEPSEEK_API_KEY` | `deepseek-v4-flash` | `https://api.deepseek.com` |

其余参数（温度、超时、重试等）使用 Spring AI 标准属性 `spring.ai.openai.*`、`spring.ai.anthropic.*`、`spring.ai.deepseek.*`。`_BASE_URL` 可指向兼容的网关或中转服务。

OpenAI 和 Anthropic 的 SDK 遵循 JVM 代理设置，在国内访问时需加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897`（Tomcat 写在 `setenv` 的 `CATALINA_OPTS`）；DeepSeek 直连即可。注意：OpenAI 会断开来自其不支持地区（含香港）的连接，代理节点需选美国、日本、新加坡等地区。

调用失败（Key 无效、限流、模型不存在、网络错误）返回 502，`msg` 中带有提供方的原始错误信息。联调真实接口可运行默认跳过的 `AiProvidersLiveTest`，它会对每个已设置 Key 的提供方各发一次普通请求和流式请求：

```shell
LOGGINGX_AI_LIVE=true LOGGINGX_AI_DEEPSEEK_API_KEY=sk-... mvn test -pl loggingx-ai -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AiProvidersLiveTest -DargLine="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
```

#### 流式输出

`POST /api/ai/chat/stream` 的请求体与 `/api/ai/chat` 相同，响应为 `text/event-stream`，模型每生成一段就推送一个事件：

```text
event:message
data:{"content":"你"}

event:message
data:{"content":"好"}

event:done
data:{"provider":"deepseek","model":"deepseek-v4-flash","content":"你好","promptTokens":7,"completionTokens":1}
```

- `message`：增量文本；`done`：结束，带完整回答、模型和 token 数（提供方未返回时为 `null`；Spring AI 的 DeepSeek 流式请求不带 `stream_options.include_usage`，token 数可能为 `null`）。
- 请求无效（未登录、参数校验失败、提供方未配置）时不会开始推送，按普通接口返回 JSON 和对应状态码。
- 推送开始后状态码已是 200，提供方报错时发送 `event:error`、`data:{"code":502,"msg":"..."}` 后结束，不再发送 `done`。
- 浏览器 `EventSource` 只支持 GET 且不能带 `Authorization` 头，前端请用 `fetch` 读取响应流（或 `@microsoft/fetch-event-source` 等库）。
- 单个流最长 5 分钟（`spring.mvc.async.request-timeout`）。经 Nginx 反向代理时需关闭该路径的缓冲（`proxy_buffering off;`），否则事件会被攒到最后一次性返回。

## 接口约定

- 响应统一为 `{"code": 200, "msg": "OK", "data": ...}`；失败时 `code` 为 HTTP 状态码，`msg` 为原因。
- 除 `/api/auth/**` 和日志上报接口 `/api/logs/ingest`（使用日志库的上报 Token）外均需携带 `Authorization: Bearer <token>`。token 绑定用户 ID；修改密码或停用账号后，该用户已签发的 token 立即失效。
- 认证失败（未登录、token 失效）返回 401，无权限返回 403，响应体同样是上述结构。
- 用户名去除首尾空格后为 2–50 位、不含空白字符，统一转小写（`UsernamePolicy`）。
- 密码长度 8–100 位（`PasswordPolicy`），注册、新增用户、修改密码统一校验。
- 同一用户名 15 分钟内连续登录失败 5 次后锁定 15 分钟，期间登录返回 429（`LoginAttemptLimiter`，状态存于 Redis，多实例共享；Redis 不可用时放行登录并记录告警）。
- 管理接口按"角色被分配的菜单"授权：能访问用户管理菜单（User）才能调用用户接口，角色管理（Role）、菜单管理（Menus）、部门管理（Dept）、岗位管理（Post）同理。
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
| GET | `/api/dept/tree` | 部门树，参数 `deptName,enabled`；筛选时保留匹配部门的上级部门 |
| POST / PUT / DELETE | `/api/dept`、`/api/dept/{id}` | 新增 / 编辑 / 删除部门（同一上级下名称唯一；有下级部门时不能删除；不能移到自身或下级之下） |
| GET | `/api/post/list` | 岗位分页，参数 `current,size,postCode,postName,enabled` |
| POST / PUT / DELETE | `/api/post`、`/api/post/{id}` | 新增 / 编辑 / 删除岗位（岗位编码唯一） |
| GET | `/api/ai/providers` | 已配置 API Key 的大模型提供方，如 `["openai","deepseek"]` |
| POST | `/api/ai/chat` | 单轮对话，body: `{"provider","message","systemPrompt?","model?"}`，返回 `{"provider","model","content","promptTokens","completionTokens"}` |
| POST | `/api/ai/chat/stream` | 流式对话（SSE），body 同上，见上文"流式输出" |

健康检查：`GET /actuator/health`
