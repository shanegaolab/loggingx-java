package com.loggingx.dao.agent;

import com.loggingx.dao.support.GeneratedKey;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agents are keyed by the id they generate; {@link #insert} refuses a key that is already registered. Deleting a store
 * deletes its agents through {@code ON DELETE CASCADE}.
 */
@Repository
public class LogAgentRepository {

    private final LogAgentMapper mapper;

    public LogAgentRepository(LogAgentMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<LogAgent> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    public Optional<LogAgent> findByKey(String agentKey) {
        return Optional.ofNullable(mapper.selectByKey(agentKey));
    }

    /**
     * @param storeId the store, or {@code null} for all agents
     * @return the agents, most recently seen first
     */
    public List<LogAgent> findByStore(Long storeId) {
        return mapper.selectByStore(storeId);
    }

    /**
     * @return for each store with agents, how many it has and how many synced since {@code onlineSince}
     */
    public List<AgentCount> countByStore(Instant onlineSince) {
        return mapper.countByStore(onlineSince);
    }

    /**
     * @return the stored agent, or empty when its key was registered concurrently
     */
    public Optional<LogAgent> insert(LogAgent agent) {
        GeneratedKey key = new GeneratedKey();
        try {
            mapper.insert(agent, key);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        return Optional.of(agent.withId(key.getId()));
    }

    public void update(LogAgent agent) {
        mapper.update(agent);
    }

    public void deleteById(Long id) {
        mapper.deleteById(id);
    }
}
