package com.loggingx.web.agent;

import com.loggingx.dao.log.LogStore;
import com.loggingx.web.agent.VectorConfig.Rendered;

import java.util.stream.Collectors;

/**
 * Renders the one-step install scripts and manifests. Each embeds the agent's first config from {@link VectorConfig}
 * and a sync script, which every 30 seconds (every minute on Windows) reports the agent to LoggingX and applies the
 * store's current config when it changed. The install command registers the agent with the store's ingest token on
 * its first sync; afterwards the agent authenticates with its own id and secret, kept in its data directory, so they
 * survive reinstalls.
 * <p>
 * Packages are downloaded through LoggingX ({@code /api/agent/download}) and checked against Vector's published
 * SHA-256 sums, so hosts only need to reach LoggingX when packages are mirrored there.
 */
final class AgentScripts {

    static final String SERVICE = VectorConfig.AGENT_NAME;
    static final String SYNC_SERVICE = SERVICE + "-sync";
    static final String WINDOWS_SYNC_TASK = "LoggingX Agent Sync";
    static final String LINUX_X86_64 = "x86_64-unknown-linux-musl";
    static final String LINUX_ARM64 = "aarch64-unknown-linux-musl";
    static final String WINDOWS_X86_64 = "x86_64-pc-windows-msvc";
    /** Seconds between syncs on Linux, Docker and Kubernetes; Windows scheduled tasks run at most every minute. */
    static final int SYNC_INTERVAL_SECONDS = 30;
    /** Delimits the embedded files; it cannot occur in them. */
    private static final String FILE_EOF = "LOGGINGX_AGENT_FILE_EOF";
    /** Where the Docker and Kubernetes agents keep the config they run. */
    private static final String CONTAINER_CONFIG_DIR = "/etc/loggingx-agent";
    /** Where the Kubernetes Secret with the first config, the scripts and the token is mounted. */
    private static final String KUBERNETES_SECRET_DIR = "/etc/loggingx-secret";

    private AgentScripts() {
    }

    static String linuxPackage(String version, String target) {
        return "vector-" + version + "-" + target + ".tar.gz";
    }

    static String windowsPackage(String version) {
        return "vector-" + version + "-" + WINDOWS_X86_64 + ".zip";
    }

    static String checksums(String version) {
        return "vector-" + version + "-SHA256SUMS";
    }

    /**
     * POSIX shell sync script, run by a systemd timer on Linux and in a loop inside the Docker and Kubernetes agent
     * containers, where only busybox {@code wget} is available.
     *
     * @param configPath the config Vector runs
     * @param stateDir   directory for the agent id and secret (Vector's data directory)
     * @param vector     the Vector program, for validating a new config
     * @param reload     shell command that makes Vector load a new config
     */
    static String syncShell(String server, String platform, String version, String configPath, String stateDir,
                            String vector, String reload) {
        return """
                #!/bin/sh
                # LoggingX agent sync: reports this agent to LoggingX (its heartbeat) and applies the config of its
                # log store when it changed. LOGGINGX_TOKEN, or a file named token next to the config, registers the
                # agent on its first sync; afterwards it authenticates with its own id and secret.
                SERVER='{{SERVER}}'
                PLATFORM='{{PLATFORM}}'
                VERSION='{{VERSION}}'
                CONFIG='{{CONFIG}}'
                STATE_DIR='{{STATE_DIR}}'
                VECTOR='{{VECTOR}}'

                say() { echo "[loggingx-sync] $*" >&2; }
                umask 077
                mkdir -p "$STATE_DIR"
                [ -s "$STATE_DIR/agent-id" ] || cat /proc/sys/kernel/random/uuid > "$STATE_DIR/agent-id"
                [ -s "$STATE_DIR/agent-secret" ] || od -An -tx1 -N32 /dev/urandom | tr -d ' \\n' > "$STATE_DIR/agent-secret"
                ID=$(cat "$STATE_DIR/agent-id")
                SECRET=$(cat "$STATE_DIR/agent-secret")
                TOKEN_FILE="$(dirname "$CONFIG")/token"
                TOKEN=${LOGGINGX_TOKEN:-}
                if [ -z "$TOKEN" ] && [ -f "$TOKEN_FILE" ]; then TOKEN=$(cat "$TOKEN_FILE"); fi
                REVISION=$(sed -n '1s/^# loggingx-revision: //p' "$CONFIG" 2>/dev/null)
                HOST=$(printf '%s' "${LOGGINGX_HOSTNAME:-$(hostname)}" | tr -cd 'A-Za-z0-9._-')
                URL="$SERVER/api/agent/sync?platform=$PLATFORM&version=$VERSION&revision=$REVISION&hostname=$HOST&server=$SERVER"
                # Vector reads the format from the extension.
                NEW="${CONFIG%.yaml}.new.yaml"
                rm -f "$NEW"

                if command -v curl >/dev/null 2>&1; then
                  set -- -H "X-Agent-Id: $ID" -H "X-Agent-Secret: $SECRET"
                  if [ -n "$TOKEN" ]; then set -- "$@" -H "X-Log-Token: $TOKEN"; fi
                  STATUS=$(curl -sS -o "$NEW" -w '%{http_code}' --max-time 20 "$@" "$URL") || STATUS=000
                else
                  set -- --header "X-Agent-Id: $ID" --header "X-Agent-Secret: $SECRET"
                  if [ -n "$TOKEN" ]; then set -- "$@" --header "X-Log-Token: $TOKEN"; fi
                  if wget -q -T 20 -O "$NEW" "$@" "$URL"; then STATUS=200; else STATUS=000; fi
                  if [ "$STATUS" = 200 ] && [ ! -s "$NEW" ]; then STATUS=204; fi
                fi

                case "$STATUS" in
                  200) ;;
                  204) rm -f "$NEW" "$TOKEN_FILE" 2>/dev/null; exit 0 ;;
                  401) say "LoggingX 不认识此 Agent（已被移除或未登记），请用日志库的安装命令重新安装"; rm -f "$NEW"; exit 1 ;;
                  *) say "同步失败（HTTP $STATUS）：$(head -c 300 "$NEW" 2>/dev/null)"; rm -f "$NEW"; exit 1 ;;
                esac
                rm -f "$TOKEN_FILE" 2>/dev/null
                if ! head -n 1 "$NEW" | grep -q '^# loggingx-revision: '; then
                  say "LoggingX 返回的不是 Agent 配置"; rm -f "$NEW"; exit 1
                fi
                if ! "$VECTOR" validate --no-environment "$NEW" >/dev/null 2>&1; then
                  say "新配置校验失败，继续使用当前配置"; rm -f "$NEW"; exit 1
                fi
                mv -f "$NEW" "$CONFIG"
                say "已应用新配置 $(sed -n '1s/^# loggingx-revision: //p' "$CONFIG")"
                {{RELOAD}}
                """
                .replace("{{SERVER}}", server)
                .replace("{{PLATFORM}}", platform)
                .replace("{{VERSION}}", version)
                .replace("{{CONFIG}}", configPath)
                .replace("{{STATE_DIR}}", stateDir)
                .replace("{{VECTOR}}", vector)
                .replace("{{RELOAD}}", reload);
    }

    /**
     * Entrypoint of the Docker and Kubernetes agent containers: syncs in the background and runs Vector as process 1,
     * which reloads its config on {@code SIGHUP}.
     *
     * @param initialConfig config to start with when none has been synced yet, or {@code null}
     * @param syncScript    path of the sync script
     */
    static String containerEntrypoint(String initialConfig, String syncScript) {
        String init = initialConfig == null ? "" : "[ -f \"$CONFIG\" ] || cp '" + initialConfig + "' \"$CONFIG\"\n";
        return """
                #!/bin/sh
                # LoggingX agent container: syncs every {{INTERVAL}} s in the background and runs Vector.
                CONFIG='{{DIR}}/vector.yaml'
                {{INIT}}( sleep 5; while :; do sh '{{SYNC}}'; sleep {{INTERVAL}}; done ) &
                exec vector --config "$CONFIG"
                """
                .replace("{{INIT}}", init)
                .replace("{{DIR}}", CONTAINER_CONFIG_DIR)
                .replace("{{SYNC}}", syncScript)
                .replace("{{INTERVAL}}", String.valueOf(SYNC_INTERVAL_SECONDS));
    }

    /**
     * Bash script that installs Vector under {@code /opt/loggingx-agent} as the systemd service {@value #SERVICE},
     * with the timer {@value #SYNC_SERVICE} syncing it; {@code bash -s -- uninstall} removes both.
     */
    static String linux(LogStore store, Rendered config, AgentProperties agent) {
        String server = config.options().serverUrl();
        String sync = syncShell(server, "linux", agent.version(), "/etc/loggingx-agent/vector.yaml",
                VectorConfig.DATA_DIR_LINUX, "/opt/loggingx-agent/bin/vector",
                "systemctl reload " + SERVICE + " || systemctl restart " + SERVICE);
        return """
                #!/usr/bin/env bash
                # LoggingX log collection agent installer (Linux, systemd) for log store '{{CODE}}'.
                # Installs Vector {{VERSION}} as the service {{SERVICE}}, which collects with the store's collect config
                # and follows its changes. Run it again to repair or upgrade; pass "uninstall" to remove the agent:
                # ... | sudo bash -s -- uninstall
                set -euo pipefail

                SERVER='{{SERVER}}'
                VERSION='{{VERSION}}'
                SERVICE='{{SERVICE}}'
                SYNC_SERVICE='{{SYNC_SERVICE}}'
                INSTALL_DIR=/opt/loggingx-agent
                CONFIG_DIR=/etc/loggingx-agent
                DATA_DIR='{{DATA_DIR}}'

                log() { printf '\\033[1;34m[loggingx]\\033[0m %s\\n' "$*"; }
                warn() { printf '\\033[1;33m[loggingx]\\033[0m %s\\n' "$*" >&2; }
                fail() { printf '\\033[1;31m[loggingx]\\033[0m %s\\n' "$*" >&2; exit 1; }

                [ "$(id -u)" -eq 0 ] || fail "请使用 root 运行（例如 ... | sudo bash）"

                if [ "${1:-}" = "uninstall" ]; then
                  systemctl disable --now "$SYNC_SERVICE.timer" "$SERVICE" >/dev/null 2>&1 || true
                  rm -f "/etc/systemd/system/$SERVICE.service" "/etc/systemd/system/$SYNC_SERVICE.service" \\
                    "/etc/systemd/system/$SYNC_SERVICE.timer"
                  systemctl daemon-reload || true
                  # Removes the agent from the store's agent list.
                  if [ -s "$DATA_DIR/agent-id" ] && command -v curl >/dev/null 2>&1; then
                    curl -fsS -X DELETE --max-time 10 -H "X-Agent-Id: $(cat "$DATA_DIR/agent-id")" \\
                      -H "X-Agent-Secret: $(cat "$DATA_DIR/agent-secret")" "$SERVER/api/agent/sync" >/dev/null 2>&1 || true
                  fi
                  rm -rf "$INSTALL_DIR" "$CONFIG_DIR"
                  log "已卸载。采集进度保存在 $DATA_DIR，不再需要时可手动删除。"
                  exit 0
                fi

                command -v systemctl >/dev/null 2>&1 || fail "需要 systemd"
                case "$(uname -m)" in
                  x86_64|amd64) TARGET='{{X86_64}}' ;;
                  aarch64|arm64) TARGET='{{ARM64}}' ;;
                  *) fail "不支持的 CPU 架构：$(uname -m)，仅支持 x86_64 与 arm64" ;;
                esac
                PACKAGE="vector-$VERSION-$TARGET.tar.gz"

                fetch() {
                  if command -v curl >/dev/null 2>&1; then curl -fsSL "$1" -o "$2"
                  elif command -v wget >/dev/null 2>&1; then wget -qO "$2" "$1"
                  else fail "需要 curl 或 wget"; fi
                }

                TMP=$(mktemp -d)
                trap 'rm -rf "$TMP"' EXIT
                if [ -x "$INSTALL_DIR/bin/vector" ] && "$INSTALL_DIR/bin/vector" --version | grep -q "vector $VERSION "; then
                  log "Vector $VERSION 已安装"
                else
                  log "下载 Vector $VERSION ($TARGET)"
                  fetch "$SERVER/api/agent/download/$PACKAGE" "$TMP/$PACKAGE"
                  fetch "$SERVER/api/agent/download/vector-$VERSION-SHA256SUMS" "$TMP/SHA256SUMS"
                  (cd "$TMP" && grep " $PACKAGE\\$" SHA256SUMS | sha256sum -c - >/dev/null) || fail "安装包 SHA-256 校验失败"
                  tar -xzf "$TMP/$PACKAGE" -C "$TMP"
                  BINARY=$(find "$TMP" -type f -path '*/bin/vector' | head -n 1)
                  [ -n "$BINARY" ] || fail "安装包中没有 vector 程序"
                  mkdir -p "$INSTALL_DIR/bin"
                  install -m 0755 "$BINARY" "$INSTALL_DIR/bin/vector"
                fi

                # The config holds the ingest token, so only root can read it.
                mkdir -p "$CONFIG_DIR" "$DATA_DIR"
                umask 077
                cat > "$CONFIG_DIR/vector.yaml" <<'{{EOF}}'
                {{CONFIG}}{{EOF}}
                "$INSTALL_DIR/bin/vector" validate --no-environment "$CONFIG_DIR/vector.yaml" >/dev/null || fail "配置校验失败"
                cat > "$INSTALL_DIR/sync.sh" <<'{{EOF}}'
                {{SYNC}}{{EOF}}

                cat > "/etc/systemd/system/$SERVICE.service" <<UNIT
                [Unit]
                Description=LoggingX log collection agent (Vector $VERSION)
                After=network-online.target
                Wants=network-online.target

                [Service]
                ExecStart=$INSTALL_DIR/bin/vector --config $CONFIG_DIR/vector.yaml
                ExecReload=/bin/kill -HUP \\$MAINPID
                Restart=always
                RestartSec=5

                [Install]
                WantedBy=multi-user.target
                UNIT

                cat > "/etc/systemd/system/$SYNC_SERVICE.service" <<UNIT
                [Unit]
                Description=LoggingX agent sync (heartbeat and config updates)
                After=network-online.target

                [Service]
                Type=oneshot
                ExecStart=/bin/sh $INSTALL_DIR/sync.sh
                UNIT

                cat > "/etc/systemd/system/$SYNC_SERVICE.timer" <<UNIT
                [Unit]
                Description=Sync the LoggingX agent every {{INTERVAL}} seconds

                [Timer]
                OnBootSec=15s
                OnUnitActiveSec={{INTERVAL}}s
                AccuracySec=5s

                [Install]
                WantedBy=timers.target
                UNIT

                systemctl daemon-reload
                systemctl enable "$SERVICE" >/dev/null 2>&1
                systemctl restart "$SERVICE"
                sleep 2
                if ! systemctl is-active --quiet "$SERVICE"; then
                  journalctl -u "$SERVICE" -n 30 --no-pager || true
                  fail "Agent 启动失败，见上方日志"
                fi
                # Registers the agent with the store; from now on the timer keeps it in sync.
                LOGGINGX_TOKEN='{{TOKEN}}' /bin/sh "$INSTALL_DIR/sync.sh" || warn "向 LoggingX 登记失败，Agent 会在之后的同步中重试"
                systemctl enable --now "$SYNC_SERVICE.timer" >/dev/null 2>&1
                log "安装完成，正在采集并发送到日志库 '{{CODE}}'；采集配置在 LoggingX 中修改后约 {{INTERVAL}} 秒内生效"
                log "状态：systemctl status $SERVICE    日志：journalctl -u $SERVICE -u $SYNC_SERVICE -f"
                """
                .replace("{{CONFIG}}", config.text())
                .replace("{{SYNC}}", sync)
                .replace("{{EOF}}", FILE_EOF)
                .replace("{{CODE}}", store.code())
                .replace("{{TOKEN}}", store.ingestToken())
                .replace("{{SERVER}}", server)
                .replace("{{VERSION}}", agent.version())
                .replace("{{SERVICE}}", SERVICE)
                .replace("{{SYNC_SERVICE}}", SYNC_SERVICE)
                .replace("{{INTERVAL}}", String.valueOf(SYNC_INTERVAL_SECONDS))
                .replace("{{DATA_DIR}}", VectorConfig.DATA_DIR_LINUX)
                .replace("{{X86_64}}", LINUX_X86_64)
                .replace("{{ARM64}}", LINUX_ARM64);
    }

    /**
     * Bash script that runs the agent as the Docker container {@value #SERVICE}. The host's file system is mounted
     * read-only below {@value VectorConfig#DOCKER_HOST_ROOT}, so paths added to the collect config later need no
     * redeployment; the Docker socket is mounted for collecting container output.
     */
    static String docker(LogStore store, Rendered config, AgentProperties agent) {
        String server = config.options().serverUrl();
        String sync = syncShell(server, "docker", agent.version(), CONTAINER_CONFIG_DIR + "/vector.yaml",
                VectorConfig.DATA_DIR_DOCKER, "vector", "kill -HUP 1");
        return """
                #!/usr/bin/env bash
                # LoggingX log collection agent installer (Docker) for log store '{{CODE}}'.
                # Runs {{IMAGE}} as the container {{SERVICE}}, which collects with the store's collect config and follows
                # its changes. Run it again to repair or upgrade; pass "uninstall" to remove the agent:
                # ... | sudo bash -s -- uninstall
                set -euo pipefail

                SERVER='{{SERVER}}'
                SERVICE='{{SERVICE}}'
                CONFIG_DIR=/etc/loggingx-agent
                DATA_DIR='{{DATA_DIR}}'

                log() { printf '\\033[1;34m[loggingx]\\033[0m %s\\n' "$*"; }
                fail() { printf '\\033[1;31m[loggingx]\\033[0m %s\\n' "$*" >&2; exit 1; }

                command -v docker >/dev/null 2>&1 || fail "需要 Docker"
                docker info >/dev/null 2>&1 || fail "无法连接 Docker，请使用 root 或 docker 组用户运行"

                if [ "${1:-}" = "uninstall" ]; then
                  docker rm -f "$SERVICE" >/dev/null 2>&1 || true
                  # Removes the agent from the store's agent list.
                  if [ -s "$DATA_DIR/agent-id" ] && command -v curl >/dev/null 2>&1; then
                    curl -fsS -X DELETE --max-time 10 -H "X-Agent-Id: $(cat "$DATA_DIR/agent-id")" \\
                      -H "X-Agent-Secret: $(cat "$DATA_DIR/agent-secret")" "$SERVER/api/agent/sync" >/dev/null 2>&1 || true
                  fi
                  rm -rf "$CONFIG_DIR"
                  log "已卸载。采集进度保存在 $DATA_DIR，不再需要时可手动删除。"
                  exit 0
                fi

                # The config and the token file hold the ingest token, so only root can read them. The container
                # registers the agent with the token on its first sync, then deletes the file.
                mkdir -p "$CONFIG_DIR" "$DATA_DIR"
                umask 077
                cat > "$CONFIG_DIR/vector.yaml" <<'{{EOF}}'
                {{CONFIG}}{{EOF}}
                cat > "$CONFIG_DIR/sync.sh" <<'{{EOF}}'
                {{SYNC}}{{EOF}}
                cat > "$CONFIG_DIR/run.sh" <<'{{EOF}}'
                {{RUN}}{{EOF}}
                printf '%s' '{{TOKEN}}' > "$CONFIG_DIR/token"

                log "启动容器 $SERVICE（{{IMAGE}}）"
                docker rm -f "$SERVICE" >/dev/null 2>&1 || true
                # Host network: the host's name and network traffic are visible to host monitoring. The host's /proc
                # and /sys give its CPU, memory, disks and processes.
                docker run -d --name "$SERVICE" --restart unless-stopped --network host \\
                  -e PROCFS_ROOT={{HOST_ROOT}}/proc -e SYSFS_ROOT={{HOST_ROOT}}/sys \\
                  -v "$CONFIG_DIR":{{CONTAINER_CONFIG_DIR}} \\
                  -v "$DATA_DIR":{{CONTAINER_DATA_DIR}} \\
                  -v /:{{HOST_ROOT}}:ro \\
                  -v /proc:{{HOST_ROOT}}/proc:ro \\
                  -v /sys:{{HOST_ROOT}}/sys:ro \\
                  -v /var/run/docker.sock:/var/run/docker.sock:ro \\
                  --entrypoint /bin/sh '{{IMAGE}}' {{CONTAINER_CONFIG_DIR}}/run.sh >/dev/null
                sleep 3
                if [ "$(docker inspect -f '{{.State.Running}}' "$SERVICE" 2>/dev/null)" != "true" ]; then
                  docker logs --tail 30 "$SERVICE" || true
                  fail "Agent 启动失败，见上方日志"
                fi
                log "安装完成，正在采集并发送到日志库 '{{CODE}}'；采集配置在 LoggingX 中修改后约 {{INTERVAL}} 秒内生效"
                log "日志：docker logs -f $SERVICE"
                """
                .replace("{{CONFIG}}", config.text())
                .replace("{{SYNC}}", sync)
                .replace("{{RUN}}", containerEntrypoint(null, CONTAINER_CONFIG_DIR + "/sync.sh"))
                .replace("{{EOF}}", FILE_EOF)
                .replace("{{CODE}}", store.code())
                .replace("{{TOKEN}}", store.ingestToken())
                .replace("{{SERVER}}", server)
                .replace("{{IMAGE}}", agent.image())
                .replace("{{SERVICE}}", SERVICE)
                .replace("{{INTERVAL}}", String.valueOf(SYNC_INTERVAL_SECONDS))
                .replace("{{HOST_ROOT}}", VectorConfig.DOCKER_HOST_ROOT)
                .replace("{{DATA_DIR}}", VectorConfig.DATA_DIR_LINUX)
                .replace("{{CONTAINER_CONFIG_DIR}}", CONTAINER_CONFIG_DIR)
                .replace("{{CONTAINER_DATA_DIR}}", VectorConfig.DATA_DIR_DOCKER);
    }

    /**
     * PowerShell sync script, run every minute by the scheduled task {@value #WINDOWS_SYNC_TASK} as SYSTEM; it
     * restarts the service to apply a new config. {@code -Token} registers the agent.
     */
    static String syncPowerShell(String server, String version) {
        return """
                # LoggingX agent sync: reports this agent to LoggingX (its heartbeat) and applies the config of its log
                # store when it changed. -Token registers the agent on its first sync; afterwards it authenticates with
                # its own id and secret.
                param([string]$Token)
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

                $Server = '{{SERVER}}'
                $Version = '{{VERSION}}'
                $Service = '{{SERVICE}}'
                $Config = Join-Path $env:ProgramData 'LoggingX Agent\\vector.yaml'
                $DataDir = '{{DATA_DIR}}'
                $Vector = Join-Path $env:ProgramFiles 'LoggingX Agent\\bin\\vector.exe'
                $Utf8 = New-Object Text.UTF8Encoding $false

                New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
                $IdFile = Join-Path $DataDir 'agent-id'
                $SecretFile = Join-Path $DataDir 'agent-secret'
                if (-not (Test-Path $IdFile)) { [IO.File]::WriteAllText($IdFile, [Guid]::NewGuid().ToString(), $Utf8) }
                if (-not (Test-Path $SecretFile)) {
                    $Bytes = New-Object byte[] 32
                    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($Bytes)
                    [IO.File]::WriteAllText($SecretFile, (($Bytes | ForEach-Object { $_.ToString('x2') }) -join ''), $Utf8)
                }
                $Headers = @{
                    'X-Agent-Id' = [IO.File]::ReadAllText($IdFile).Trim()
                    'X-Agent-Secret' = [IO.File]::ReadAllText($SecretFile).Trim()
                }
                if ($Token) { $Headers['X-Log-Token'] = $Token }

                $Revision = ''
                if (Test-Path $Config) {
                    $First = [IO.File]::ReadAllLines($Config) | Select-Object -First 1
                    if ($First -match '^# loggingx-revision: ([a-f0-9]+)') { $Revision = $Matches[1] }
                }
                $Name = $env:COMPUTERNAME -replace '[^\\w.-]', ''
                $Url = "$Server/api/agent/sync?platform=windows&version=$Version&revision=$Revision&hostname=$Name&server=$([Uri]::EscapeDataString($Server))"
                $Response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Headers $Headers -TimeoutSec 20
                if ($Response.StatusCode -ne 200) { return }

                $Content = $Response.Content
                if ($Content -is [byte[]]) { $Content = $Utf8.GetString($Content) }
                if (-not $Content.StartsWith('# loggingx-revision: ')) { throw 'LoggingX 返回的不是 Agent 配置' }
                $New = [IO.Path]::ChangeExtension($Config, '.new.yaml')
                [IO.File]::WriteAllText($New, $Content, $Utf8)
                & $Vector validate --no-environment $New | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Remove-Item -Force $New
                    throw '新配置校验失败，继续使用当前配置'
                }
                Move-Item -Force $New $Config
                Restart-Service -Name $Service
                """
                .replace("{{SERVER}}", server)
                .replace("{{VERSION}}", version)
                .replace("{{SERVICE}}", SERVICE)
                .replace("{{DATA_DIR}}", VectorConfig.DATA_DIR_WINDOWS);
    }

    /**
     * PowerShell script that installs Vector under {@code Program Files\LoggingX Agent} as the Windows service
     * {@value #SERVICE}, synced by the scheduled task {@value #WINDOWS_SYNC_TASK}; invoking it with
     * {@code -Uninstall} removes both.
     */
    static String windows(LogStore store, Rendered config, AgentProperties agent) {
        String server = config.options().serverUrl();
        return """
                # LoggingX log collection agent installer (Windows) for log store '{{CODE}}'.
                # Installs Vector {{VERSION}} as the Windows service {{SERVICE}}, which collects with the store's collect
                # config and follows its changes. Run it again to repair or upgrade; add -Uninstall to remove the agent.
                # Run in PowerShell as Administrator.
                param([switch]$Uninstall)
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

                $Server = '{{SERVER}}'
                $Version = '{{VERSION}}'
                $Service = '{{SERVICE}}'
                $SyncTask = '{{SYNC_TASK}}'
                $Package = "vector-$Version-{{TARGET}}.zip"
                $InstallDir = Join-Path $env:ProgramFiles 'LoggingX Agent'
                $ConfigDir = Join-Path $env:ProgramData 'LoggingX Agent'
                $DataDir = '{{DATA_DIR}}'
                $Config = Join-Path $ConfigDir 'vector.yaml'
                $Vector = Join-Path $InstallDir 'bin\\vector.exe'
                $SyncScript = Join-Path $InstallDir 'sync.ps1'

                function Write-Step($Text) { Write-Host "[loggingx] $Text" -ForegroundColor Cyan }

                $Principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
                if (-not $Principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
                    throw '请以管理员身份运行 PowerShell'
                }

                function Remove-AgentService {
                    if (Get-Service -Name $Service -ErrorAction SilentlyContinue) {
                        Stop-Service -Name $Service -Force -ErrorAction SilentlyContinue
                        if (Test-Path $Vector) { & $Vector service uninstall --name $Service | Out-Null }
                        else { sc.exe delete $Service | Out-Null }
                    }
                }

                if ($Uninstall) {
                    Unregister-ScheduledTask -TaskName $SyncTask -Confirm:$false -ErrorAction SilentlyContinue
                    Remove-AgentService
                    # Removes the agent from the store's agent list.
                    $IdFile = Join-Path $DataDir 'agent-id'
                    if (Test-Path $IdFile) {
                        try {
                            $Headers = @{
                                'X-Agent-Id' = [IO.File]::ReadAllText($IdFile).Trim()
                                'X-Agent-Secret' = [IO.File]::ReadAllText((Join-Path $DataDir 'agent-secret')).Trim()
                            }
                            Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "$Server/api/agent/sync" -Headers $Headers -TimeoutSec 10 | Out-Null
                        } catch { }
                    }
                    Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue
                    Remove-Item -Force $Config -ErrorAction SilentlyContinue
                    Write-Step "已卸载。采集进度保存在 $DataDir，不再需要时可手动删除。"
                    return
                }

                $Installed = (Test-Path $Vector) -and ((& $Vector --version) -match "vector $Version ")
                if ($Installed) {
                    Write-Step "Vector $Version 已安装"
                } else {
                    Write-Step "下载 Vector $Version"
                    $Temp = Join-Path $env:TEMP ('loggingx-agent-' + [Guid]::NewGuid())
                    New-Item -ItemType Directory -Path $Temp | Out-Null
                    try {
                        $Zip = Join-Path $Temp $Package
                        Invoke-WebRequest -UseBasicParsing -Uri "$Server/api/agent/download/$Package" -OutFile $Zip
                        $Sums = (Invoke-WebRequest -UseBasicParsing -Uri "$Server/api/agent/download/vector-$Version-SHA256SUMS").Content
                        if ($Sums -is [byte[]]) { $Sums = [Text.Encoding]::UTF8.GetString($Sums) }
                        $Line = ($Sums -split "`n") | Where-Object { $_.Trim().EndsWith($Package) } | Select-Object -First 1
                        $Expected = if ($Line) { ($Line -split '\\s+')[0] } else { '' }
                        $Actual = (Get-FileHash -Path $Zip -Algorithm SHA256).Hash
                        if (-not $Expected -or $Expected -ne $Actual.ToLower()) { throw '安装包 SHA-256 校验失败' }
                        Expand-Archive -Path $Zip -DestinationPath (Join-Path $Temp 'vector') -Force
                        Remove-AgentService
                        New-Item -ItemType Directory -Path (Join-Path $InstallDir 'bin') -Force | Out-Null
                        Copy-Item -Path (Join-Path $Temp 'vector\\bin\\vector.exe') -Destination $Vector -Force
                    } finally {
                        Remove-Item -Recurse -Force $Temp -ErrorAction SilentlyContinue
                    }
                }

                # The config holds the ingest token, and the data directory the agent's secret, so only Administrators
                # and SYSTEM can read them.
                New-Item -ItemType Directory -Path $ConfigDir, $DataDir -Force | Out-Null
                icacls $ConfigDir /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-18:(OI)(CI)F' | Out-Null
                $Yaml = @'
                {{CONFIG}}'@
                [IO.File]::WriteAllText($Config, $Yaml, (New-Object Text.UTF8Encoding $false))
                & $Vector validate --no-environment $Config | Out-Null
                if ($LASTEXITCODE -ne 0) { throw '配置校验失败' }
                # Windows PowerShell reads a script as UTF-8 only with a BOM.
                $Sync = @'
                {{SYNC}}'@
                [IO.File]::WriteAllText($SyncScript, $Sync, (New-Object Text.UTF8Encoding $true))

                Remove-AgentService
                & $Vector service install --name $Service --display-name 'LoggingX Agent' --config-yaml $Config | Out-Null
                Set-Service -Name $Service -StartupType Automatic
                Start-Service -Name $Service
                Start-Sleep -Seconds 2
                if ((Get-Service -Name $Service).Status -ne 'Running') { throw 'Agent 启动失败，请查看事件查看器中的应用程序日志' }

                # Registers the agent with the store; from now on the scheduled task keeps it in sync.
                # A separate process, as the execution policy may forbid running script files in this one.
                powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $SyncScript -Token '{{TOKEN}}'
                if ($LASTEXITCODE -ne 0) { Write-Warning '向 LoggingX 登记失败，Agent 会在之后的同步中重试' }
                $Action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$SyncScript`""
                $Trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
                $Settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
                Register-ScheduledTask -TaskName $SyncTask -Action $Action -Trigger $Trigger -Settings $Settings -User 'SYSTEM' -RunLevel Highest -Force | Out-Null
                Write-Step "安装完成，正在采集并发送到日志库 '{{CODE}}'；采集配置在 LoggingX 中修改后约 1 分钟内生效"
                Write-Step "状态：Get-Service $Service；同步任务：Get-ScheduledTaskInfo -TaskName '$SyncTask'"
                """
                .replace("{{CONFIG}}", config.text())
                .replace("{{SYNC}}", syncPowerShell(server, agent.version()))
                .replace("{{CODE}}", store.code())
                .replace("{{TOKEN}}", store.ingestToken())
                .replace("{{SERVER}}", server)
                .replace("{{VERSION}}", agent.version())
                .replace("{{SERVICE}}", SERVICE)
                .replace("{{SYNC_TASK}}", WINDOWS_SYNC_TASK)
                .replace("{{TARGET}}", WINDOWS_X86_64)
                .replace("{{DATA_DIR}}", VectorConfig.DATA_DIR_WINDOWS);
    }

    /**
     * Kubernetes manifest: a namespace, RBAC to read pod metadata, a Secret with the first config, the scripts and the
     * ingest token, and a DaemonSet that runs the agent on every node. Each pod syncs on its own and registers as an
     * agent named after its node.
     */
    static String kubernetes(LogStore store, Rendered config, AgentProperties agent) {
        String server = config.options().serverUrl();
        String sync = syncShell(server, "kubernetes", agent.version(), CONTAINER_CONFIG_DIR + "/vector.yaml",
                VectorConfig.DATA_DIR_KUBERNETES, "vector", "kill -HUP 1");
        String run = containerEntrypoint(KUBERNETES_SECRET_DIR + "/vector.yaml", KUBERNETES_SECRET_DIR + "/sync.sh");
        return """
                # LoggingX log collection agent (Kubernetes) for log store '{{CODE}}'.
                # Collects the logs of all pods except the kube-system and {{NS}} namespaces, on every node.
                # Apply: kubectl apply -f <this file>    Remove: kubectl delete namespace {{NS}}; kubectl delete clusterrole,clusterrolebinding {{NAME}}
                apiVersion: v1
                kind: Namespace
                metadata:
                  name: {{NS}}
                ---
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: {{NAME}}
                  namespace: {{NS}}
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: {{NAME}}
                rules:
                  - apiGroups: [""]
                    resources: ["namespaces", "nodes", "pods"]
                    verbs: ["get", "list", "watch"]
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRoleBinding
                metadata:
                  name: {{NAME}}
                roleRef:
                  apiGroup: rbac.authorization.k8s.io
                  kind: ClusterRole
                  name: {{NAME}}
                subjects:
                  - kind: ServiceAccount
                    name: {{NAME}}
                    namespace: {{NS}}
                ---
                # The config and the token are secrets. Pods start with this config and then follow the store's
                # collect config, which they sync every {{INTERVAL}} seconds.
                apiVersion: v1
                kind: Secret
                metadata:
                  name: {{NAME}}-config
                  namespace: {{NS}}
                type: Opaque
                stringData:
                  token: '{{TOKEN}}'
                  vector.yaml: |
                {{CONFIG}}
                  sync.sh: |
                {{SYNC}}
                  run.sh: |
                {{RUN}}
                ---
                apiVersion: apps/v1
                kind: DaemonSet
                metadata:
                  name: {{NAME}}
                  namespace: {{NS}}
                  labels:
                    app.kubernetes.io/name: {{NAME}}
                spec:
                  selector:
                    matchLabels:
                      app.kubernetes.io/name: {{NAME}}
                  template:
                    metadata:
                      labels:
                        app.kubernetes.io/name: {{NAME}}
                    spec:
                      serviceAccountName: {{NAME}}
                      # The node's network, so host monitoring sees the node's traffic.
                      hostNetwork: true
                      dnsPolicy: ClusterFirstWithHostNet
                      # Runs on every node, control-plane nodes included.
                      tolerations:
                        - operator: Exists
                      containers:
                        - name: vector
                          image: '{{IMAGE}}'
                          command: ["/bin/sh", "{{SECRET_DIR}}/run.sh"]
                          env:
                            - name: LOGGINGX_TOKEN
                              valueFrom:
                                secretKeyRef:
                                  name: {{NAME}}-config
                                  key: token
                            - name: LOGGINGX_HOSTNAME
                              valueFrom:
                                fieldRef:
                                  fieldPath: spec.nodeName
                            # Host monitoring reads the node's /proc and /sys.
                            - name: PROCFS_ROOT
                              value: /host/proc
                            - name: SYSFS_ROOT
                              value: /host/sys
                            - name: VECTOR_SELF_NODE_NAME
                              valueFrom:
                                fieldRef:
                                  fieldPath: spec.nodeName
                            - name: VECTOR_SELF_POD_NAME
                              valueFrom:
                                fieldRef:
                                  fieldPath: metadata.name
                            - name: VECTOR_SELF_POD_NAMESPACE
                              valueFrom:
                                fieldRef:
                                  fieldPath: metadata.namespace
                          resources:
                            requests:
                              cpu: 50m
                              memory: 64Mi
                            limits:
                              memory: 512Mi
                          volumeMounts:
                            - name: secret
                              mountPath: {{SECRET_DIR}}
                              readOnly: true
                            - name: config
                              mountPath: {{CONFIG_DIR}}
                            - name: data
                              mountPath: {{CONTAINER_DATA_DIR}}
                            - name: var-log
                              mountPath: /var/log
                              readOnly: true
                            - name: var-lib
                              mountPath: /var/lib
                              readOnly: true
                            - name: proc
                              mountPath: /host/proc
                              readOnly: true
                            - name: sys
                              mountPath: /host/sys
                              readOnly: true
                      volumes:
                        - name: secret
                          secret:
                            secretName: {{NAME}}-config
                        # The config the pod runs, as last synced.
                        - name: config
                          emptyDir: {}
                        - name: data
                          hostPath:
                            path: {{DATA_DIR}}
                            type: DirectoryOrCreate
                        - name: var-log
                          hostPath:
                            path: /var/log
                        - name: var-lib
                          hostPath:
                            path: /var/lib
                        - name: proc
                          hostPath:
                            path: /proc
                        - name: sys
                          hostPath:
                            path: /sys
                """
                .replace("{{CONFIG}}", indent(config.text()))
                .replace("{{SYNC}}", indent(sync))
                .replace("{{RUN}}", indent(run))
                .replace("{{TOKEN}}", store.ingestToken())
                .replace("{{CODE}}", store.code())
                .replace("{{NS}}", VectorConfig.KUBERNETES_NAMESPACE)
                .replace("{{NAME}}", SERVICE)
                .replace("{{IMAGE}}", agent.image())
                .replace("{{INTERVAL}}", String.valueOf(SYNC_INTERVAL_SECONDS))
                .replace("{{SECRET_DIR}}", KUBERNETES_SECRET_DIR)
                .replace("{{CONFIG_DIR}}", CONTAINER_CONFIG_DIR)
                .replace("{{DATA_DIR}}", VectorConfig.DATA_DIR_LINUX)
                .replace("{{CONTAINER_DATA_DIR}}", VectorConfig.DATA_DIR_KUBERNETES);
    }

    /**
     * @return the text indented as a YAML block scalar under a {@code stringData} key
     */
    private static String indent(String text) {
        return text.lines().map(line -> line.isEmpty() ? "" : "    " + line).collect(Collectors.joining("\n"));
    }
}
