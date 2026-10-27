package com.loggingx.dao.agent;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A collection agent installed on a host or Kubernetes node, which syncs its config with LoggingX.
 *
 * @param agentKey       random id the agent generated on installation, unique across stores
 * @param secretHash     SHA-256 (hex) of the secret the agent authenticates its syncs with
 * @param platform       {@code linux}, {@code docker}, {@code kubernetes} or {@code windows}
 * @param serverUrl      LoggingX address the agent reaches, which its config sends logs to
 * @param configRevision revision of the config the agent runs, as reported on its latest sync
 * @param lastSeenAt     time of the latest sync
 */
public record LogAgent(
        Long id,
        String agentKey,
        String secretHash,
        Long storeId,
        String hostname,
        String platform,
        String version,
        String ip,
        String serverUrl,
        String configRevision,
        Instant registeredAt,
        Instant lastSeenAt
) {

    public LogAgent {
        registeredAt = Timestamps.storable(registeredAt);
        lastSeenAt = Timestamps.storable(lastSeenAt);
    }

    public LogAgent withId(Long newId) {
        return new LogAgent(newId, agentKey, secretHash, storeId, hostname, platform, version, ip, serverUrl,
                configRevision, registeredAt, lastSeenAt);
    }
}
