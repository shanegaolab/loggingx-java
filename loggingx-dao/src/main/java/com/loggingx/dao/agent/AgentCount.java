package com.loggingx.dao.agent;

/**
 * @param total  agents of the store
 * @param online agents that synced since the given time
 */
public record AgentCount(Long storeId, long total, long online) {
}
