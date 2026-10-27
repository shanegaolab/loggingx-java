# 日志采集 Agent：选型与部署

LoggingX 用 [Vector](https://vector.dev) 作为日志采集 Agent。每个日志库有一份**采集配置**（采集哪些文件、多行规则、传输方式），在「日志管理 → 采集 Agent」页面或日志库卡片上的 Agent 面板里选择部署环境，即可得到一键安装命令、安装脚本、Vector 配置和 Agent 安装包。安装后的 Agent 会登记到日志库并定期同步：采集配置的修改、Token 的重置都会自动下发。本文说明选型理由、生成的配置做了什么、各环境如何部署和运维。

## 选型

### 候选

| | Vector | Fluent Bit | Filebeat | iLogtail / LoongCollector | OpenTelemetry Collector |
| --- | --- | --- | --- | --- | --- |
| 实现语言 / 体积 | Rust，单个静态二进制 | C，最轻量 | Go | C++ / Go | Go |
| 许可证 | MPL-2.0 | Apache-2.0 | Elastic 许可（默认发行版） | Apache-2.0 | Apache-2.0 |
| HTTP 输出 | ✅（自定义请求头、批量、重试） | ✅ | ❌（只有 ES / Logstash / Kafka / Redis） | ✅ | ✅（OTLP 为主） |
| Kafka 输出 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 磁盘缓冲（断网不丢） | ✅ 内置 | ✅（filesystem storage） | ✅（磁盘队列） | ✅ | 需扩展 |
| 多行合并 | ✅ | ✅ | ✅ | ✅ | 有限 |
| 行偏移（保证同一时间的日志顺序） | ✅ `offset_key` | ✅ `Offset_Key` | ✅ `log.offset` | ✅ | 有限 |
| 数据加工 | VRL 脚本，可解析 JSON、提取级别和时间 | Lua / 内置过滤器 | 处理器（较弱） | 插件 | Processor |
| Linux / Windows / Docker / K8s | 全部官方支持；Windows 自带服务注册 | 全部支持 | 全部支持 | Linux 为主 | 全部支持 |
| 配置校验 | `vector validate` | `--dry-run` | `test config` | 无 | `validate` |

### 结论：默认 Vector

1. **能直接对接 LoggingX 的两种入口。** HTTP 输出可以带 `X-Log-Token` 请求头、按 1000 条分批，Kafka 输出可以按主机分区；Filebeat 没有 HTTP 输出，只能走 Kafka。
2. **转换能力强。** 一段 VRL 脚本就能把各种格式的日志行整理成 LoggingX 的入库格式：解析 JSON 日志、从行首识别级别和时间（按 Agent 机器的时区换算），不需要在服务端为每种格式写解析规则。
3. **可靠。** 内置磁盘缓冲：LoggingX 不可用或网络中断时日志先落盘，恢复后补发，Agent 重启也不丢；读取进度保存在数据目录，重启后从上次位置继续。
4. **部署简单。** 单个静态二进制（Linux 用 musl 版，不依赖系统库），Windows 自带 `vector service install`，官方提供 Docker 镜像和 Kubernetes 日志源。
5. **能在本地验证。** 每份生成的配置都可以用 `vector validate` 检查，CI 中也能做。

什么时候选别的：

- **Fluent Bit**：机器资源非常紧张（边缘设备、IoT），或团队已大规模使用。它的 HTTP 输出用 `Format json_lines` 即可直接接入 LoggingX（见下文「接入其他采集器」）。
- **Filebeat**：已经有 Elastic 体系并统一走 Kafka 时可以继续用；LoggingX 能识别它的 `@timestamp`、`log.offset`、`log.file.path`、`host.name`。
- **iLogtail / LoongCollector**：主要在阿里云环境中使用时可考虑。

## 生成的配置做了什么

数据流：`来源 → normalize（VRL 转换）→ loggingx（HTTP 或 Kafka 输出）`。配置由 `VectorConfig` 生成，每个日志库一份。

**来源**

| 环境 | 来源 | 说明 |
| --- | --- | --- |
| Linux / Windows | `file` | 采集填写的路径（支持通配符）；没有读取进度的文件（新文件、Agent 停机期间轮转出的文件）从头读取，一天未修改的旧文件跳过；`offset_key: offset` 记录每行的字节偏移 |
| Docker | `docker_logs` + 可选 `file` | 采集本机所有容器的标准输出（排除 Agent 自身）；宿主机根目录以只读方式挂载到容器的 `/host`，文件路径前加上 `/host` 读取 |
| Kubernetes | `kubernetes_logs` | 每个节点上的 DaemonSet 采集所有 Pod 的日志，跳过 `kube-system` 和 `loggingx` 命名空间 |

**多行合并**（Kubernetes 不支持）

| 选项 | 规则 | 适用 |
| --- | --- | --- |
| 不合并 | 每行一条 | 单行日志 |
| 异常堆栈（默认） | 不缩进的行开始新日志；缩进行、`Caused by:`、`Suppressed:`、异常类名行（`...Exception: ...`）并入上一条 | Java / Python / .NET 等的异常堆栈 |
| 以日期开头为新日志 | 以 `2026-10-23` 这样的日期（可带 `[`）开头的行开始新日志 | 每条日志都以时间开头的应用 |
| 自定义 | 匹配正则的行开始新日志 | 其他格式 |

**转换（normalize）**，把每条日志整理成 LoggingX 的入库格式：

1. 行内容是 JSON 对象时并入事件，`level`、`time`、`traceId` 等字段直接使用，其余字段作为附加字段保存。
2. 没有级别时，从行首 160 个字符里找 `TRACE/DEBUG/INFO/WARN/WARNING/ERROR/FATAL/CRITICAL`（只认大写单词，避免把正文里的 "error" 当成级别）。
3. 没有时间时，取行首的时间戳（如 `2026-10-23 10:00:00.123`、`2026-10-23T10:00:00Z`）。没带时区的按 **Agent 机器的时区**换算；仍然没有就用 Agent 读到该行的时间。
4. 文件路径作为来源 `source`，字节偏移作为行序号 `seq`：同一时间的多行日志靠它保持文件中的顺序。容器日志的来源为容器名，Kubernetes 的主机为 Pod 名、来源为 `命名空间/容器`。

**输出**

- **HTTP（推荐）**：`POST <LoggingX 地址>/api/logs/ingest`，请求头 `X-Log-Token` 为日志库的上报 Token，每批最多 1000 条、最多等 1 秒。
- **Kafka**：发到指定 Topic，每条日志带 `logstore` 字段（日志库编码），按 `host` 分区以保持每台主机的日志顺序。LoggingX 后端需开启 `LOGGINGX_KAFKA_ENABLED=true`。
- 两者都用 1 GiB 磁盘缓冲并开启端到端确认，见下文「可靠性」。

## 可靠性：不丢日志与断点续传

投递语义是**至少一次**（at-least-once）：日志只要已写入日志文件，就会送达，极端情况下可能重复，不会丢失。

| 场景 | 处理 |
| --- | --- |
| LoggingX 宕机、网络中断 | 失败的请求（网络错误、超时、5xx、429）无限重试，退避上限 60 秒；期间日志进入**磁盘缓冲**（每个输出 1 GiB）。缓冲写满后暂停读取，未读的内容留在日志文件里，恢复后接着读 |
| Agent 重启、崩溃、断电 | 读取进度（checkpoint）和缓冲都在数据目录，重启后从断点继续。开启了**端到端确认**：日志写进磁盘缓冲后，文件的读取进度才向前推进，所以崩溃时最多重读一小段，不会跳过 |
| Agent 停机期间日志轮转 | 新文件没有读取进度，从头读取，停机期间写入的内容不会漏；轮转出的旧文件只要仍匹配采集路径（如 `app.log*`）也会读完剩余内容 |
| 请求已入库但响应丢失 | Agent 重试时请求体完全相同，请求头 `X-Log-Dedup: batch` 让后端按「Token + 请求体」的 SHA-256 识别，30 分钟内重复的批次直接确认、不再入库（多实例经 Redis 共享，Redis 不可用时退回本机内存）。只有成功入库的批次才会被记住，失败的请求重试时照常入库 |

数据目录：Linux `/var/lib/loggingx-agent`，Windows `C:\ProgramData\LoggingX Agent\data`，Docker 挂载宿主机目录，Kubernetes 用节点的 hostPath，都会在重启、重建容器后保留。

**边界**：

- 应用自己还没写进磁盘的日志（应用进程内的缓冲、操作系统页缓存）在断电时会丢，这不在采集器的控制范围内。
- 采集路径只写 `app.log` 时，Agent 长时间停机且轮转后的文件名（如 `app.log.1`）不在采集路径里，旧文件剩余的内容读不到。建议采集路径写成 `app.log*` 或 `*.log*`（Vector 按内容指纹识别文件，改名不会重复采集）。
- 停机时间长到轮转把文件删除（如 `logrotate` 的 `rotate 3`），被删除的部分无法恢复。
- Docker 容器标准输出（`docker_logs`）不支持端到端确认，依靠磁盘缓冲；Agent 停机期间的容器输出从 Agent 启动时开始采集。需要严格不丢的容器日志，建议让应用写文件并采集文件。
- 崩溃后重读的少量日志会重复入库（它们与之前的请求批次不同，无法按批次去重）。
- Token 被重置、日志库停用或删除时，后端返回 401 / 403，这些批次不会重试。

2026-10-27 用 Vector 0.58（Windows）实测：采集 1500 行后让 LoggingX 不可达，再写 2500 行；强制结束 Agent 进程（模拟崩溃）；Agent 停机期间再追加 500 行、把 `app.log` 轮转为 `app.log.1`，并在新的 `app.log` 写入 1000 行；恢复网络并启动 Agent。5500 行全部送达，没有重复。

## 与日志库联动：登记、心跳与配置下发

```text
            安装命令（带上报 Token）
  Agent ──────────────────────────────► 首次同步：登记到日志库，生成自己的 id 和密钥
    │
    │ 每 30 秒（Windows 每分钟）GET /api/agent/sync，带 id、密钥和当前配置的版本号
    ▼
 LoggingX ── 配置没变：204（记一次心跳） ── 配置变了：200 + 新配置 → Agent 校验后应用并重载
```

- **采集配置存在日志库上**：Linux / Docker 宿主机路径、Windows 路径、是否采集容器输出、多行规则、传输方式（HTTP / Kafka）。没保存过时使用默认配置（`/var/log/app/*.log`、`C:\logs\app\*.log`、异常堆栈、HTTP）。安装命令只决定部署环境和 LoggingX 地址，采集内容都来自这份配置。
- **版本号**：每份下发给 Agent 的配置第一行是 `# loggingx-revision: <版本号>`，版本号是配置内容（含 Token 和 LoggingX 地址）的 SHA-256 前 16 位。Agent 同步时带上当前版本号，不同时才会收到新配置。
- **Agent 身份**：首次同步用日志库的上报 Token 登记；Agent 同时生成随机的 id 和密钥，保存在数据目录（`agent-id`、`agent-secret`，重装后沿用），LoggingX 只保存密钥的 SHA-256。之后的同步只凭 id 和密钥，所以**重置 Token 后 Agent 仍能同步，并自动换用新 Token**。用另一个日志库的命令重装，Agent 会转到那个日志库。
- **应用新配置**：先用 `vector validate` 校验，通过后替换配置并让 Vector 重新加载（Linux 为 `systemctl reload`，Docker / Kubernetes 为向容器内的 Vector 发 `SIGHUP`，Windows 为重启服务）；校验不通过时保留当前配置。
- **在线状态**：3 分钟内同步过的 Agent 为在线。Agent 列表还会显示配置是否已同步（「待同步」表示配置或 Token 变了、Agent 还没取到，下次同步后变为「已同步」）、版本是否需要升级（重新执行安装命令即可）。

日志库管理中的联动：

| 操作 | 对 Agent 的影响 |
| --- | --- |
| 修改采集配置 | 已登记的 Agent 约 1 分钟内应用，无需重新部署；Docker 宿主机根目录已只读挂载到容器的 `/host`，新增路径也不用重建容器 |
| 重置 Token | 已登记的 Agent 下次同步时换用新 Token；手动部署的 Agent 和其他采集器需要手动更新 |
| 停用日志库 | Agent 继续运行和同步，上报的日志被拒绝（403），重新启用后自动恢复 |
| 删除日志库 | 该日志库的 Agent 记录一并删除，之后同步返回 401；请在这些主机上执行卸载命令 |
| 在列表中移除 Agent | 只删除记录、不停止采集；Agent 不再能用密钥同步，但仍持有有效 Token 时（如 Kubernetes 的 Secret）会重新登记。怀疑 Token 泄露时先移除可疑的 Agent 再重置 Token |
| 卸载 Agent | 卸载命令会顺带把自己从列表中移除 |

## 主机监控

同一个 Agent 还会采集所在主机的资源情况（Vector 的 `host_metrics` 数据源），在「监控中心 → 主机监控」查看。日志库的「采集配置 → 主机监控」控制是否开启（默认开启）、采集间隔（默认 15 秒）、是否上报进程 Top 和容器资源，修改后像其他采集配置一样自动下发。

**采集的指标**

| 指标 | Linux | Docker 宿主机 | Kubernetes 节点 | Windows |
| --- | --- | --- | --- | --- |
| CPU 使用率、核数 | ✅ | ✅ | ✅ | ✅ |
| 内存、Swap | ✅ | ✅ | ✅ | ✅ |
| 磁盘读写速率、各文件系统使用率 | ✅ | ✅ | ✅ | ✅ |
| 网络收发速率 | ✅ | ✅ | ✅ | ❌（Vector 不支持） |
| 系统负载（1/5/15 分钟） | ✅ | ✅ | ✅ | ❌ |
| 进程 Top | ✅ | ✅ | ✅ | ✅ |
| 容器与服务（cgroups v2） | ✅ | ✅ | ✅ | ❌ |

- **计算方式**：CPU 按「所有核、所有模式」的累计秒数计算，空闲和 I/O 等待算空闲。磁盘只统计整块磁盘（跳过分区、loop、dm 等，避免重复）；网络只统计物理网卡（跳过 lo、veth、docker、cni 等虚拟网卡）；文件系统跳过 tmpfs、overlay 等伪文件系统。速率在查询时由相邻两次采集计算，服务端不保存状态。
- **进程 Top**：每 30 秒采集一次，Agent 端只上报 CPU ≥ 0.5% 或内存 ≥ 50 MB 的进程（一台主机通常有几百个进程，全部上报每天约 2 GB），服务端每次保留 CPU 和内存各前 15 名，保存 3 天。CPU 为单核百分比，多核进程可超过 100%。
- **容器与服务**：来自 Linux 的 cgroups v2，列出 Docker 容器（显示容器 ID 前 12 位）、Kubernetes 容器和 systemd 服务。容器名无法从 cgroups 得到，可在 `docker ps` 中按 ID 对照。
- **传输**：指标单独走 HTTP（即使日志走 Kafka），gzip 压缩，一次采集在一个请求里发送；使用内存缓冲，LoggingX 不可用时丢弃最新的指标，不占用日志的磁盘缓冲，也不会阻塞日志采集。
- **容器内采集宿主机**：Docker 容器使用宿主机网络（`--network host`）并只读挂载宿主机的 `/proc`、`/sys`；Kubernetes DaemonSet 使用 `hostNetwork` 并挂载节点的 `/proc`、`/sys`，以节点名上报，与 Agent 登记的主机名一致。
- **存储**：与日志相同（ClickHouse 的 `host_sample`、`host_filesystem`、`host_process`、`host_container` 表，见 `db/clickhouse-monitor-schema.sql`；`LOGGINGX_LOG_STORAGE=memory` 时存在内存）。保留天数由 `LOGGINGX_MONITOR_RETENTION_DAYS` 设置，默认 15 天。

**页面**

- **主机监控**：所有主机的在线状态、CPU、内存、最满的磁盘、负载、网络、运行时长和告警数，可按日志库、状态筛选，每 30 秒刷新。点开主机看 1 小时到 7 天的趋势图（CPU、内存、负载、磁盘读写、网络、磁盘使用率），点击图表选中某个时刻，查看当时的进程 Top，或跳转到该主机前后 5 分钟的日志。日志查询的详情里也能从主机名跳回主机监控。
- **告警中心**：告警事件和告警规则。

**告警规则**

后台每分钟评估一次所有启用的规则；条件成立时为该主机产生一条「告警中」的事件（同一规则和主机只有一条，持续更新当前值和峰值），条件不再成立或主机不再上报时自动恢复。首次启动时创建以下默认规则，可在告警中心修改、停用或新增：

| 默认规则 | 条件 | 级别 |
| --- | --- | --- |
| CPU 使用率过高 | 5 分钟平均 CPU > 90% | 严重 |
| 内存使用率过高 | 5 分钟内内存始终 > 90% | 警告 |
| 磁盘空间不足 | 任一文件系统 > 85% | 警告 |
| 系统负载过高 | 10 分钟内每核负载（1 分钟负载 ÷ 核数）始终 > 2 | 警告 |
| 主机离线 | Agent 超过 3 分钟未同步 | 严重 |

持续类规则要求这段时间内的采集至少覆盖 60%，刚开始上报的主机不会误报。规则可以只作用于某个日志库。告警事件保留 30 天。目前只在页面上展示，还没有邮件、钉钉等通知。

## 部署

页面生成的命令把 Token 放在请求头里，不出现在 URL 中（避免被代理和服务器访问日志记录）。采集配置的修改会自动下发，一般不需要重新执行命令；重新执行可以修复安装或升级 Agent 版本（版本不变时不会重新下载）。

| 环境 | 命令做了什么 | 查看状态 | 卸载 |
| --- | --- | --- | --- |
| Linux（systemd，x86_64 / arm64） | 下载并校验 Vector（SHA-256）→ 安装到 `/opt/loggingx-agent` → 配置写入 `/etc/loggingx-agent/vector.yaml`（权限 600）→ `vector validate` → 注册并启动 systemd 服务 `loggingx-agent` → 登记 → 启用每 30 秒运行一次的同步定时器 `loggingx-agent-sync.timer` | `systemctl status loggingx-agent`、`journalctl -u loggingx-agent -u loggingx-agent-sync -f` | 同一命令末尾改为 `\| sudo bash -s -- uninstall` |
| Docker | 写入配置和同步脚本 → 以 `timberio/vector:<版本>-alpine` 运行容器 `loggingx-agent`（`--restart unless-stopped`，使用宿主机网络，宿主机 `/`、`/proc`、`/sys` 只读挂载到 `/host` 下）；容器内后台每 30 秒同步一次，首次同步用一次性的 Token 文件登记 | `docker logs -f loggingx-agent` | 同上 |
| Kubernetes | `kubectl apply`：命名空间 `loggingx`、ServiceAccount 与只读 RBAC、Secret（初始配置、同步脚本、Token）、DaemonSet（容忍所有污点，在每个节点运行，使用节点网络并只读挂载节点的 `/proc`、`/sys`）；每个 Pod 以所在节点名登记为一个 Agent，后台每 30 秒同步 | `kubectl -n loggingx get pods`、`kubectl -n loggingx logs -l app.kubernetes.io/name=loggingx-agent` | `kubectl delete namespace loggingx; kubectl delete clusterrole,clusterrolebinding loggingx-agent`（Agent 记录需在列表中移除） |
| Windows（管理员 PowerShell） | 下载并校验 → 安装到 `C:\Program Files\LoggingX Agent` → 配置写入 `C:\ProgramData\LoggingX Agent\vector.yaml`（仅管理员和 SYSTEM 可读）→ 校验 → 注册并启动 Windows 服务 `loggingx-agent` → 登记 → 注册以 SYSTEM 每分钟运行的计划任务 `LoggingX Agent Sync` | `Get-Service loggingx-agent`、`Get-ScheduledTaskInfo -TaskName 'LoggingX Agent Sync'`，日志在事件查看器 | 同一命令末尾加 ` -Uninstall` |

采集进度（数据目录，也保存 Agent 的 id 和密钥）在卸载时保留：Linux / Docker / Kubernetes 节点上为 `/var/lib/loggingx-agent`，Windows 为 `C:\ProgramData\LoggingX Agent\data`，不再需要时可手动删除。

「手动安装」（自己下载安装包和配置）得到的 Agent 不会登记，也不会自动同步配置。

### 后端配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LOGGINGX_AGENT_VERSION` | `0.58.0` | 安装的 Vector 版本；升级时修改后在各机器重新执行安装命令 |
| `LOGGINGX_AGENT_SERVER_URL` | 空（使用打开页面的地址） | Agent 访问 LoggingX 的地址，如 `https://logs.example.com`；LoggingX 在反向代理之后时建议设置 |
| `LOGGINGX_AGENT_PACKAGE_DIR` | 空 | 本地安装包镜像目录，见下文 |
| `LOGGINGX_AGENT_UPSTREAM_URL` | `https://github.com/vectordotdev/vector/releases/download` | 未镜像时安装包的来源 |
| `LOGGINGX_AGENT_IMAGE` | `timberio/vector:<版本>-alpine` | Docker / Kubernetes 镜像，可改为内网镜像仓库地址 |

### 内网环境

Agent 只从 LoggingX 下载安装包（`/api/agent/download/<文件>`）。默认会重定向到 GitHub；机器不能访问外网时，把以下文件放到 `LOGGINGX_AGENT_PACKAGE_DIR` 目录，LoggingX 会直接提供：

```text
vector-0.58.0-x86_64-unknown-linux-musl.tar.gz
vector-0.58.0-aarch64-unknown-linux-musl.tar.gz
vector-0.58.0-x86_64-pc-windows-msvc.zip
vector-0.58.0-SHA256SUMS
```

文件可从 <https://github.com/vectordotdev/vector/releases/tag/v0.58.0> 下载。安装脚本会用 `SHA256SUMS` 校验安装包。Docker / Kubernetes 需要把镜像同步到内网仓库并设置 `LOGGINGX_AGENT_IMAGE`。

## 排查

| 现象 | 原因与处理 |
| --- | --- |
| 安装后查不到日志 | `read_from: end` 只采集安装之后新写入的内容，先往日志文件里写一行再看；确认路径正确、Agent 有读权限（Linux 以 root 运行） |
| 日志时间差了几个小时 | 行首时间没有时区时按 Agent 机器的时区换算，检查该机器的时区设置 |
| 异常堆栈被拆成多条 | 选择「异常堆栈」或「以日期开头为新日志」；格式特殊时用自定义正则 |
| Agent 显示离线 | 超过 3 分钟没有同步：检查主机能否访问 LoggingX；Linux 看 `journalctl -u loggingx-agent-sync`，Windows 看计划任务的上次运行结果 |
| 一直「待同步」 | Agent 离线，或新配置在 Agent 上校验失败（同步日志中有「新配置校验失败」），检查采集配置 |
| 「无可采集内容」 | 采集配置里没有适用于该环境的路径（如 Windows 主机但只填了 Linux 路径） |
| 同步返回 401 | Agent 已被移除、日志库已删除，或数据目录里的密钥丢失：用安装命令重新安装 |
| 上报返回 401 | 手动部署的 Agent 仍在用旧 Token：换用新 Token，或改用安装命令部署 |
| 上报返回 403 | 日志库已停用 |
| 改了 LoggingX 地址 | 在新地址下重新执行安装命令，缓冲中未发送的日志会发到新地址 |

## 接入其他采集器

LoggingX 的上报接口不绑定 Vector。接口格式见后端 README「日志上报」。

**Fluent Bit**（HTTP）：

```ini
[INPUT]
    Name        tail
    Path        /var/log/app/*.log
    Offset_Key  offset

[OUTPUT]
    Name        http
    Match       *
    Host        logs.example.com
    Port        443
    tls         On
    URI         /api/logs/ingest
    Format      json_lines
    Header      X-Log-Token lx_xxx
```

**Filebeat**（Kafka，LoggingX 开启 Kafka 消费后）：

```yaml
filebeat.inputs:
  - type: filestream
    paths: [/var/log/app/*.log]
    fields: { logstore: order-service }
    fields_under_root: true
output.kafka:
  hosts: ["kafka-1:9092"]
  topic: loggingx-logs
  key: "%{[host.name]}"
```
