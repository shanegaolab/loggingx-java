package com.loggingx.biz.agent;

import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.dao.agent.AgentCount;
import com.loggingx.dao.agent.CollectConfig;
import com.loggingx.dao.agent.CollectConfigRepository;
import com.loggingx.dao.agent.LogAgent;
import com.loggingx.dao.agent.LogAgentRepository;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The collection agents of the log stores, and what they collect.
 * <p>
 * An agent generates an id and a secret when it is installed and syncs with LoggingX every 30-60 seconds: each sync
 * records it as seen (its heartbeat) and hands it the current config of its store. The first sync carries the store's
 * ingest token and registers the agent; later syncs authenticate with the agent's secret alone, so agents keep syncing,
 * and receive the new token in their config, after the store's token is regenerated. A sync with a valid token of
 * another store moves the agent there (it was reinstalled for that store).
 */
@Service
public class AgentService {

    /** Agents sync every 30 s (60 s on Windows); one that has not synced for this long is offline. */
    public static final Duration ONLINE_WINDOW = Duration.ofMinutes(3);
    public static final Set<String> PLATFORMS = Set.of("linux", "docker", "kubernetes", "windows");

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9]{32,128}");
    private static final Pattern VERSION = Pattern.compile("[\\w.+-]{1,32}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9]{1,32}");
    private static final Pattern HOSTNAME_UNSAFE = Pattern.compile("[^\\w.-]");
    private static final String AGENT_NOT_FOUND = "Agent was not found";

    private final LogAgentRepository agentRepository;
    private final CollectConfigRepository collectConfigRepository;
    private final LogStoreRepository logStoreRepository;

    public AgentService(LogAgentRepository agentRepository, CollectConfigRepository collectConfigRepository,
                        LogStoreRepository logStoreRepository) {
        this.agentRepository = agentRepository;
        this.collectConfigRepository = collectConfigRepository;
        this.logStoreRepository = logStoreRepository;
    }

    /**
     * What an agent reports on a sync.
     *
     * @param token     the store's ingest token; required to register, optional afterwards
     * @param revision  revision of the config the agent runs, or {@code null} before it has one
     * @param serverUrl the LoggingX address the agent reaches
     */
    public record SyncCommand(String agentKey, String secret, String token, String hostname, String platform,
                              String version, String ip, String serverUrl, String revision) {
    }

    /**
     * @param agent the agent as recorded by this sync
     * @param store the store it collects for
     */
    public record Synced(LogAgent agent, LogStore store) {
    }

    /**
     * Records a sync (registering the agent on its first one).
     *
     * @throws UnauthorizedException when neither the agent's secret nor the token is valid
     */
    public Synced sync(SyncCommand command) {
        String key = command.agentKey() == null ? "" : command.agentKey().trim();
        String secret = command.secret() == null ? "" : command.secret().trim();
        if (!KEY.matcher(key).matches() || !SECRET.matcher(secret).matches()) {
            throw new UnauthorizedException("Agent id or secret is missing or malformed");
        }
        String platform = command.platform() == null ? "" : command.platform().trim().toLowerCase();
        if (!PLATFORMS.contains(platform)) {
            throw new BadRequestException("platform must be one of " + PLATFORMS);
        }

        String hash = sha256(secret);
        Optional<LogAgent> existing = agentRepository.findByKey(key);
        boolean authenticated = existing.filter(agent -> MessageDigest.isEqual(
                agent.secretHash().getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII))).isPresent();
        // A token that no longer works (it was regenerated) is ignored once the agent authenticates by its secret.
        Optional<LogStore> tokenStore = command.token() == null || command.token().isBlank() ? Optional.empty()
                : logStoreRepository.findByIngestToken(command.token().trim());
        if (!authenticated && tokenStore.isEmpty()) {
            throw new UnauthorizedException(existing.isPresent()
                    ? "Agent secret does not match; reinstall the agent with the store's install command"
                    : "Agent is not registered; register it with the store's ingest token");
        }
        LogStore store = tokenStore.orElseGet(() -> logStoreRepository.findById(existing.get().storeId())
                .orElseThrow(() -> new UnauthorizedException("The agent's log store was deleted")));

        Instant now = Instant.now();
        String revision = command.revision() != null && REVISION.matcher(command.revision()).matches()
                ? command.revision() : null;
        String version = command.version() != null && VERSION.matcher(command.version()).matches()
                ? command.version() : null;
        String ip = command.ip() == null ? null : command.ip().substring(0, Math.min(64, command.ip().length()));
        String server = command.serverUrl() == null ? null
                : command.serverUrl().substring(0, Math.min(255, command.serverUrl().length()));
        LogAgent agent = new LogAgent(existing.map(LogAgent::id).orElse(null), key, hash, store.id(),
                hostname(command.hostname()), platform, version, ip, server, revision,
                existing.map(LogAgent::registeredAt).orElse(now), now);
        if (existing.isPresent()) {
            agentRepository.update(agent);
            if (!existing.get().storeId().equals(store.id())) {
                log.info("event=agent.move agentKey={} hostname={} fromStoreId={} toStoreId={}",
                        key, agent.hostname(), existing.get().storeId(), store.id());
            }
            return new Synced(agent, store);
        }
        Optional<LogAgent> inserted = agentRepository.insert(agent);
        if (inserted.isEmpty()) {
            // Registered concurrently by a retry of the same agent.
            LogAgent current = agentRepository.findByKey(key)
                    .orElseThrow(() -> new UnauthorizedException("Agent registration failed; try again"));
            agent = agent.withId(current.id());
            agentRepository.update(agent);
            return new Synced(agent, store);
        }
        log.info("event=agent.register agentKey={} hostname={} platform={} storeId={} code={}",
                key, agent.hostname(), platform, store.id(), store.code());
        return new Synced(inserted.get(), store);
    }

    /**
     * Removes the agent with this id and secret; the agent's uninstaller calls it. Unknown agents are ignored.
     */
    public void deregister(String agentKey, String secret) {
        if (agentKey == null || secret == null) {
            return;
        }
        agentRepository.findByKey(agentKey.trim())
                .filter(agent -> agent.secretHash().equals(sha256(secret.trim())))
                .ifPresent(agent -> {
                    agentRepository.deleteById(agent.id());
                    log.info("event=agent.deregister agentKey={} hostname={} storeId={}",
                            agent.agentKey(), agent.hostname(), agent.storeId());
                });
    }

    /**
     * @param storeId the store, or {@code null} for the agents of all stores
     * @return the agents, most recently seen first
     */
    public List<LogAgent> list(Long storeId) {
        return agentRepository.findByStore(storeId);
    }

    /**
     * @return per store id, how many agents it has and how many are online; stores without agents are absent
     */
    public Map<Long, AgentCount> counts() {
        return agentRepository.countByStore(Instant.now().minus(ONLINE_WINDOW)).stream()
                .collect(Collectors.toMap(AgentCount::storeId, Function.identity()));
    }

    public static boolean isOnline(LogAgent agent) {
        return agent.lastSeenAt() != null && agent.lastSeenAt().isAfter(Instant.now().minus(ONLINE_WINDOW));
    }

    /**
     * @return the agent, if it is registered
     */
    public Optional<LogAgent> find(Long id) {
        return agentRepository.findById(id);
    }

    /**
     * Forgets an agent. It registers again on its next sync only if it presents a valid ingest token.
     */
    public void remove(Long id, String operatorUsername) {
        LogAgent agent = agentRepository.findById(id).orElseThrow(() -> new NotFoundException(AGENT_NOT_FOUND));
        agentRepository.deleteById(id);
        log.info("event=agent.remove operator={} agentKey={} hostname={} storeId={}",
                operatorUsername, agent.agentKey(), agent.hostname(), agent.storeId());
    }

    /**
     * @return the store's collect config, or the defaults when it has not saved one
     */
    public CollectConfig collectConfig(Long storeId) {
        requireStore(storeId);
        return collectConfigRepository.findByStore(storeId).orElseGet(() -> CollectConfig.defaults(storeId));
    }

    /**
     * Saves a validated collect config; the store's agents pick it up on their next sync.
     */
    public CollectConfig saveCollectConfig(CollectConfig config, String operatorUsername) {
        requireStore(config.storeId());
        CollectConfig saved = new CollectConfig(config.storeId(), config.paths(), config.windowsPaths(),
                config.containers(), config.multiline(), config.multilinePattern(), config.transport(),
                config.kafkaServers(), config.kafkaTopic(), Instant.now());
        collectConfigRepository.save(saved);
        log.info("event=agent.collect.update operator={} storeId={} transport={} paths={} windowsPaths={}",
                operatorUsername, config.storeId(), config.transport(), config.paths().size(),
                config.windowsPaths().size());
        return saved;
    }

    private void requireStore(Long storeId) {
        if (storeId == null || logStoreRepository.findById(storeId).isEmpty()) {
            throw new NotFoundException("Log store was not found");
        }
    }

    private static String hostname(String value) {
        String clean = value == null ? "" : HOSTNAME_UNSAFE.matcher(value.trim()).replaceAll("");
        if (clean.isEmpty()) {
            return "unknown";
        }
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
