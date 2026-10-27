package com.loggingx.web.agent.dto;

import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.agent.LogAgent;
import com.loggingx.dao.log.LogStore;

/**
 * An installed agent as shown in the agent list.
 *
 * @param online    whether it synced within the online window
 * @param syncState {@code synced}, {@code pending} (it has yet to apply the current config) or {@code invalid}
 *                  (the collect config has nothing to collect on its platform)
 * @param outdated  whether it runs another Vector version than LoggingX installs
 */
public record AgentResponse(
        Long agentId,
        Long storeId,
        String storeCode,
        String storeName,
        String hostname,
        String platform,
        String version,
        String ip,
        String serverUrl,
        boolean online,
        String syncState,
        boolean outdated,
        String lastSeenTime,
        String registerTime
) {

    public static AgentResponse from(LogAgent agent, LogStore store, boolean online, String syncState,
                                     String currentVersion) {
        return new AgentResponse(agent.id(), store.id(), store.code(), store.name(), agent.hostname(),
                agent.platform(), agent.version(), agent.ip(), agent.serverUrl(), online, syncState,
                agent.version() != null && !agent.version().equals(currentVersion),
                DateTimes.format(agent.lastSeenAt()), DateTimes.format(agent.registeredAt()));
    }
}
