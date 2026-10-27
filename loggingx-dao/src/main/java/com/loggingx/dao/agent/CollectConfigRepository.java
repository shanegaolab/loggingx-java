package com.loggingx.dao.agent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Deleting a store deletes its config through {@code ON DELETE CASCADE}.
 */
@Repository
public class CollectConfigRepository {

    private final CollectConfigMapper mapper;

    public CollectConfigRepository(CollectConfigMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @return the store's saved config, or empty when it uses the defaults
     */
    public Optional<CollectConfig> findByStore(Long storeId) {
        CollectConfigMapper.Row row = mapper.selectByStore(storeId);
        return row == null ? Optional.empty() : Optional.of(new CollectConfig(row.storeId(), lines(row.paths()),
                lines(row.windowsPaths()), row.containers(), row.multiline(), row.multilinePattern(),
                row.transport(), row.kafkaServers(), row.kafkaTopic(), row.updatedAt()));
    }

    /**
     * Inserts or replaces the store's config.
     */
    public void save(CollectConfig config) {
        CollectConfigMapper.Row row = new CollectConfigMapper.Row(config.storeId(), join(config.paths()),
                join(config.windowsPaths()), config.containers(), config.multiline(), config.multilinePattern(),
                config.transport(), config.kafkaServers(), config.kafkaTopic(), config.updatedAt());
        if (mapper.update(row) > 0) {
            return;
        }
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            // Saved concurrently; the later save wins.
            mapper.update(row);
        }
    }

    private static List<String> lines(String value) {
        return value == null || value.isEmpty() ? List.of() : Arrays.asList(value.split("\n"));
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? null : String.join("\n", values);
    }
}
