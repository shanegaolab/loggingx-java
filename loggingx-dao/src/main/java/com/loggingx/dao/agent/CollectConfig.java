package com.loggingx.dao.agent;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;
import java.util.List;

/**
 * What the agents of a log store collect and where they send it. Agents fetch the config rendered from it, so a change
 * reaches them on their next sync.
 *
 * @param paths            log files (globs allowed) on Linux hosts and Docker hosts
 * @param windowsPaths     log files on Windows hosts
 * @param containers       Docker only: also collect the output of all containers
 * @param multiline        {@code none}, {@code stacktrace}, {@code timestamp} or {@code custom}
 * @param multilinePattern {@code custom} only: regex matching the first line of each entry
 * @param transport        {@code http} or {@code kafka}
 * @param updatedAt        when the config was last saved, or {@code null} for the defaults
 */
public record CollectConfig(
        Long storeId,
        List<String> paths,
        List<String> windowsPaths,
        boolean containers,
        String multiline,
        String multilinePattern,
        String transport,
        String kafkaServers,
        String kafkaTopic,
        Instant updatedAt
) {

    public CollectConfig {
        paths = paths == null ? List.of() : List.copyOf(paths);
        windowsPaths = windowsPaths == null ? List.of() : List.copyOf(windowsPaths);
        updatedAt = Timestamps.storable(updatedAt);
    }

    /**
     * @return the config of a store that has not saved one: application log files with stack traces, over HTTP
     */
    public static CollectConfig defaults(Long storeId) {
        return new CollectConfig(storeId, List.of("/var/log/app/*.log"), List.of("C:\\logs\\app\\*.log"), true,
                "stacktrace", null, "http", null, null, null);
    }
}
