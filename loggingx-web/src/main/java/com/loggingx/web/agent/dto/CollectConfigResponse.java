package com.loggingx.web.agent.dto;

import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.agent.CollectConfig;
import com.loggingx.dao.monitor.MonitorConfig;

import java.util.List;

/**
 * @param updateTime when the config was last saved, or {@code null} when the store uses the defaults
 * @param monitor    host resource monitoring
 */
public record CollectConfigResponse(
        Long storeId,
        List<String> paths,
        List<String> windowsPaths,
        boolean containers,
        String multiline,
        String multilinePattern,
        String transport,
        String kafkaServers,
        String kafkaTopic,
        String updateTime,
        Monitor monitor
) {

    public record Monitor(boolean enabled, int intervalSeconds, boolean processes, boolean containers) {
    }

    public static CollectConfigResponse from(CollectConfig config, MonitorConfig monitor) {
        return new CollectConfigResponse(config.storeId(), config.paths(), config.windowsPaths(), config.containers(),
                config.multiline(), config.multilinePattern(), config.transport(), config.kafkaServers(),
                config.kafkaTopic(), DateTimes.format(config.updatedAt()),
                new Monitor(monitor.enabled(), monitor.intervalSeconds(), monitor.processes(), monitor.containers()));
    }
}
