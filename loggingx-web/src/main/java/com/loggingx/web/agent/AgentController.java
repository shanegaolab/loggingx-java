package com.loggingx.web.agent;

import com.loggingx.biz.agent.AgentService;
import com.loggingx.biz.agent.AgentService.SyncCommand;
import com.loggingx.biz.agent.AgentService.Synced;
import com.loggingx.biz.log.LogStoreService;
import com.loggingx.biz.monitor.MonitorService;
import com.loggingx.biz.permission.PermissionService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.dao.agent.CollectConfig;
import com.loggingx.dao.agent.LogAgent;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.MonitorConfig;
import com.loggingx.web.agent.AgentOptions.Platform;
import com.loggingx.web.agent.VectorConfig.Rendered;
import com.loggingx.web.agent.dto.AgentResponse;
import com.loggingx.web.agent.dto.CollectConfigRequest;
import com.loggingx.web.agent.dto.CollectConfigResponse;
import com.loggingx.web.log.LogIngestController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deploys and manages the log collection agents (Vector).
 * <p>
 * Each log store has a collect config (what its agents collect). Install scripts and configs are rendered from it for
 * one platform; they are requested either by an agent host with the store's ingest token in the
 * {@value LogIngestController#TOKEN_HEADER} header (the one-step install commands do this, so the token never appears
 * in a URL), or by a user who manages log stores, with the store's id. Installed agents sync through
 * {@code /api/agent/sync}, which records them and hands out the current config. Packages are public, as they are on
 * Vector's own release page.
 */
@RestController
@RequestMapping("/agent")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentController {

    static final String AGENT_ID_HEADER = "X-Agent-Id";
    static final String AGENT_SECRET_HEADER = "X-Agent-Secret";
    static final String REVISION_HEADER = "X-Config-Revision";

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml;charset=UTF-8");
    private static final MediaType SHELL = MediaType.parseMediaType("text/x-shellscript;charset=UTF-8");
    private static final MediaType PLAIN = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String MANAGERS = "@permissionService.hasAnyMenu(authentication, 'LogStore', 'LogAgent')";

    private final AgentProperties agent;
    private final LogStoreRepository logStoreRepository;
    private final LogStoreService logStoreService;
    private final AgentService agentService;
    private final MonitorService monitorService;
    private final PermissionService permissionService;
    private final boolean kafkaEnabled;
    private final String kafkaServers;
    private final String kafkaTopic;

    public AgentController(
            AgentProperties agent,
            LogStoreRepository logStoreRepository,
            LogStoreService logStoreService,
            AgentService agentService,
            MonitorService monitorService,
            PermissionService permissionService,
            @Value("${loggingx.log.kafka.enabled:false}") boolean kafkaEnabled,
            @Value("${spring.kafka.bootstrap-servers:}") String kafkaServers,
            @Value("${loggingx.log.kafka.topics:loggingx-logs}") String kafkaTopics
    ) {
        this.agent = agent;
        this.logStoreRepository = logStoreRepository;
        this.logStoreService = logStoreService;
        this.agentService = agentService;
        this.monitorService = monitorService;
        this.permissionService = permissionService;
        this.kafkaEnabled = kafkaEnabled;
        this.kafkaServers = kafkaServers;
        this.kafkaTopic = kafkaTopics.split(",")[0].trim();
    }

    /**
     * @param platform operating system or orchestrator: {@code linux}, {@code docker}, {@code windows}
     * @param arch     CPU architecture
     * @param file     package name, downloaded from {@code /api/agent/download/<file>}
     */
    public record AgentPackage(String platform, String arch, String file) {
    }

    /**
     * @param serverUrl     configured base URL for agents, or {@code null} to use the page's own address
     * @param kafka         the Kafka settings LoggingX consumes with, offered as defaults for the Kafka transport
     * @param onlineSeconds an agent that has not synced for this long is offline
     */
    public record AgentMeta(String version, String image, List<AgentPackage> packages, String checksums,
                            String serverUrl, KafkaDefaults kafka, long onlineSeconds) {
    }

    /**
     * @param enabled whether LoggingX consumes from Kafka; agents publishing there are otherwise not read
     */
    public record KafkaDefaults(boolean enabled, String servers, String topic) {
    }

    @GetMapping("/meta")
    @PreAuthorize(MANAGERS)
    public ApiResponse<AgentMeta> meta() {
        String version = agent.version();
        List<AgentPackage> packages = List.of(
                new AgentPackage("linux", "x86_64", AgentScripts.linuxPackage(version, AgentScripts.LINUX_X86_64)),
                new AgentPackage("linux", "arm64", AgentScripts.linuxPackage(version, AgentScripts.LINUX_ARM64)),
                new AgentPackage("windows", "x86_64", AgentScripts.windowsPackage(version)));
        return ApiResponse.success(new AgentMeta(version, agent.image(), packages, AgentScripts.checksums(version),
                agent.serverUrl().isEmpty() ? null : agent.serverUrl(),
                new KafkaDefaults(kafkaEnabled, kafkaServers, kafkaTopic), AgentService.ONLINE_WINDOW.toSeconds()));
    }

    // ---------- collect config and agents (log store managers) ----------

    @GetMapping("/collect")
    @PreAuthorize(MANAGERS)
    public ApiResponse<CollectConfigResponse> collectConfig(@RequestParam Long storeId) {
        return ApiResponse.success(CollectConfigResponse.from(agentService.collectConfig(storeId),
                monitorService.config(storeId)));
    }

    /**
     * Saves the store's collect config; its agents apply it on their next sync.
     */
    @PutMapping("/collect")
    @PreAuthorize(MANAGERS)
    public ApiResponse<CollectConfigResponse> saveCollectConfig(@RequestParam Long storeId,
                                                                @RequestBody CollectConfigRequest request,
                                                                Authentication authentication) {
        CollectConfig config = AgentOptions.validate(request.toConfig(storeId));
        agentService.collectConfig(storeId);
        // Saved first: it validates its values, so an invalid request changes nothing.
        MonitorConfig monitor = request.monitor() == null ? monitorService.config(storeId)
                : monitorService.saveConfig(request.monitor().toConfig(storeId));
        CollectConfig saved = agentService.saveCollectConfig(config, authentication.getName());
        return ApiResponse.success(CollectConfigResponse.from(saved, monitor));
    }

    /**
     * @param storeId the store, or none for the agents of all stores
     */
    @GetMapping("/agents")
    @PreAuthorize(MANAGERS)
    public ApiResponse<List<AgentResponse>> agents(@RequestParam(required = false) Long storeId) {
        Map<Long, LogStore> stores = new HashMap<>();
        Map<Long, CollectConfig> configs = new HashMap<>();
        Map<Long, MonitorConfig> monitors = new HashMap<>();
        List<AgentResponse> agents = agentService.list(storeId).stream().map(item -> {
            LogStore store = stores.computeIfAbsent(item.storeId(),
                    id -> logStoreRepository.findById(id).orElse(null));
            if (store == null) {
                return null;
            }
            CollectConfig collect = configs.computeIfAbsent(item.storeId(), agentService::collectConfig);
            MonitorConfig monitor = monitors.computeIfAbsent(item.storeId(), monitorService::config);
            return AgentResponse.from(item, store, AgentService.isOnline(item),
                    syncState(item, store, collect, monitor),
                    agent.version());
        }).filter(java.util.Objects::nonNull).toList();
        return ApiResponse.success(agents);
    }

    @DeleteMapping("/agents/{id}")
    @PreAuthorize(MANAGERS)
    public ApiResponse<Void> removeAgent(@PathVariable Long id, Authentication authentication) {
        agentService.remove(id, authentication.getName());
        return ApiResponse.success(null);
    }

    /**
     * @return {@code synced} when the agent runs the config it would receive now, {@code pending} when it has yet to
     * apply it, {@code invalid} when the collect config has nothing to collect on its platform
     */
    private static String syncState(LogAgent item, LogStore store, CollectConfig collect,
                                    MonitorConfig monitor) {
        try {
            Platform platform = Platform.valueOf(item.platform().toUpperCase(Locale.ROOT));
            Rendered rendered = VectorConfig.render(store, collect, monitor, platform, item.serverUrl());
            return rendered.revision().equals(item.configRevision()) ? "synced" : "pending";
        } catch (BadRequestException | IllegalArgumentException | NullPointerException e) {
            return "invalid";
        }
    }

    // ---------- agent sync (agents themselves) ----------

    /**
     * The agents' heartbeat and config delivery. Records the agent (registering it when the store's ingest token
     * is sent) and answers 204 when it runs the current config, or 200 with the config to apply.
     *
     * @param revision revision of the config the agent runs
     * @param server   the LoggingX address the agent reaches, which its config sends logs to
     */
    @GetMapping("/sync")
    public ResponseEntity<String> sync(@RequestParam String platform,
                                       @RequestParam(required = false) String version,
                                       @RequestParam(required = false) String revision,
                                       @RequestParam(required = false) String hostname,
                                       @RequestParam(required = false) String server,
                                       @RequestHeader(value = AGENT_ID_HEADER, required = false) String agentId,
                                       @RequestHeader(value = AGENT_SECRET_HEADER, required = false) String secret,
                                       @RequestHeader(value = LogIngestController.TOKEN_HEADER, required = false)
                                       String token,
                                       HttpServletRequest request) {
        Platform parsed = parsePlatform(platform);
        String serverUrl = serverUrl(server, request);
        Synced synced = agentService.sync(new SyncCommand(agentId, secret, token, hostname,
                parsed.name().toLowerCase(Locale.ROOT), version, clientIp(request), serverUrl, revision));
        Long storeId = synced.store().id();
        Rendered rendered = VectorConfig.render(synced.store(), agentService.collectConfig(storeId),
                monitorService.config(storeId), parsed, serverUrl);
        if (rendered.revision().equals(revision)) {
            return ResponseEntity.noContent().header(REVISION_HEADER, rendered.revision()).build();
        }
        return ResponseEntity.ok().contentType(PLAIN).header(REVISION_HEADER, rendered.revision())
                .body(rendered.text());
    }

    /**
     * Removes the calling agent; its uninstaller calls this.
     */
    @DeleteMapping("/sync")
    public ResponseEntity<Void> deregister(@RequestHeader(value = AGENT_ID_HEADER, required = false) String agentId,
                                           @RequestHeader(value = AGENT_SECRET_HEADER, required = false)
                                           String secret) {
        agentService.deregister(agentId, secret);
        return ResponseEntity.noContent().build();
    }

    // ---------- configs, install scripts and packages ----------

    /**
     * Request parameters shared by the config and script endpoints, bound from the query string. What is collected
     * comes from the store's collect config.
     *
     * @param server   the LoggingX address agents reach, by default this request's
     * @param download whether the response is saved as a file rather than run directly
     */
    public record AgentParams(Long storeId, String platform, String server, Boolean download) {
    }

    @GetMapping(value = "/config", produces = "application/yaml")
    public ResponseEntity<String> config(AgentParams params, HttpServletRequest request,
                                         Authentication authentication,
                                         @RequestHeader(value = LogIngestController.TOKEN_HEADER, required = false)
                                         String token) {
        LogStore store = resolveStore(token, params.storeId(), authentication);
        Rendered config = render(store, parsePlatform(params.platform()), params, request);
        return text(config.text(), YAML, "vector.yaml");
    }

    @GetMapping(value = "/install.sh", produces = "text/x-shellscript")
    public ResponseEntity<String> installShell(AgentParams params, HttpServletRequest request,
                                               Authentication authentication,
                                               @RequestHeader(value = LogIngestController.TOKEN_HEADER,
                                                       required = false) String token) {
        LogStore store = resolveStore(token, params.storeId(), authentication);
        Platform platform = parsePlatform(params.platform());
        Rendered config = render(store, platform, params, request);
        String script = switch (platform) {
            case LINUX -> AgentScripts.linux(store, config, agent);
            case DOCKER -> AgentScripts.docker(store, config, agent);
            default -> throw new BadRequestException("install.sh is for Linux and Docker; use install.ps1 for "
                    + "Windows and kubernetes.yaml for Kubernetes");
        };
        return text(script, SHELL, "install-loggingx-agent.sh");
    }

    @GetMapping(value = "/install.ps1", produces = "text/plain")
    public ResponseEntity<String> installPowerShell(AgentParams params, HttpServletRequest request,
                                                    Authentication authentication,
                                                    @RequestHeader(value = LogIngestController.TOKEN_HEADER,
                                                            required = false) String token) {
        LogStore store = resolveStore(token, params.storeId(), authentication);
        String script = AgentScripts.windows(store, render(store, Platform.WINDOWS, params, request), agent);
        // A saved file needs a BOM for Windows PowerShell 5.1 to read it as UTF-8; the one-step command passes the
        // text straight to PowerShell, where a BOM would be an unexpected character.
        String body = Boolean.TRUE.equals(params.download()) ? BOM + script : script;
        return text(body, PLAIN, "install-loggingx-agent.ps1");
    }

    @GetMapping(value = "/kubernetes.yaml", produces = "application/yaml")
    public ResponseEntity<String> kubernetes(AgentParams params, HttpServletRequest request,
                                             Authentication authentication,
                                             @RequestHeader(value = LogIngestController.TOKEN_HEADER,
                                                     required = false) String token) {
        LogStore store = resolveStore(token, params.storeId(), authentication);
        return text(AgentScripts.kubernetes(store, render(store, Platform.KUBERNETES, params, request), agent),
                YAML, "loggingx-agent.yaml");
    }

    /**
     * Serves a Vector package or its checksum file: from {@code loggingx.agent.package-dir} when mirrored there,
     * otherwise by redirecting to the upstream release.
     */
    @GetMapping("/download/{file:.+}")
    public ResponseEntity<Resource> download(@PathVariable String file) {
        String version = agent.version();
        Set<String> known = Set.of(AgentScripts.linuxPackage(version, AgentScripts.LINUX_X86_64),
                AgentScripts.linuxPackage(version, AgentScripts.LINUX_ARM64), AgentScripts.windowsPackage(version),
                AgentScripts.checksums(version));
        if (!known.contains(file)) {
            throw new NotFoundException("Unknown agent package '" + file + "'");
        }
        if (!agent.packageDir().isEmpty()) {
            Path local = Path.of(agent.packageDir()).resolve(file);
            if (Files.isRegularFile(local)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(file).build().toString())
                        .body(new FileSystemResource(local));
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(agent.upstreamUrl() + "/v" + version + "/" + file))
                .build();
    }

    private Rendered render(LogStore store, Platform platform, AgentParams params, HttpServletRequest request) {
        String serverUrl = serverUrl(params.server(), request);
        return VectorConfig.render(store, agentService.collectConfig(store.id()), monitorService.config(store.id()),
                platform, serverUrl);
    }

    private static Platform parsePlatform(String platform) {
        try {
            return platform == null || platform.isBlank() ? Platform.LINUX
                    : Platform.valueOf(platform.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("platform '" + platform + "' is not supported");
        }
    }

    /**
     * @return the validated server URL, or the default one when none was given
     */
    private String serverUrl(String server, HttpServletRequest request) {
        // Kubernetes needs no paths, so this only checks the server URL.
        return AgentOptions.of(Platform.KUBERNETES.name(), null, List.of(), false, null, null, server, null, null)
                .withDefaultServer(defaultServerUrl(request)).serverUrl();
    }

    /**
     * @return the configured agent server URL, or the base URL of this request
     */
    private String defaultServerUrl(HttpServletRequest request) {
        if (!agent.serverUrl().isEmpty()) {
            return agent.serverUrl();
        }
        return ServletUriComponentsBuilder.fromContextPath(request).replacePath(request.getContextPath())
                .replaceQuery(null).build().toUriString();
    }

    /**
     * @return the first {@code X-Forwarded-For} address when LoggingX is behind a proxy, else the peer address
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private LogStore resolveStore(String token, Long storeId, Authentication authentication) {
        if (token != null && !token.isBlank()) {
            LogStore store = logStoreRepository.findByIngestToken(token.trim())
                    .orElseThrow(() -> new UnauthorizedException("Ingest token is invalid"));
            if (!store.enabled()) {
                throw new ForbiddenException("Log store '" + store.code() + "' is disabled");
            }
            return store;
        }
        if (authentication == null || !authentication.isAuthenticated()
                || !permissionService.hasAnyMenu(authentication, "LogStore", "LogAgent")) {
            throw new UnauthorizedException("Send the log store's ingest token in the "
                    + LogIngestController.TOKEN_HEADER + " header, or sign in as a log store manager");
        }
        if (storeId == null) {
            throw new BadRequestException("storeId is required");
        }
        return logStoreService.require(storeId);
    }

    private static ResponseEntity<String> text(String body, MediaType type, String fileName) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
