package com.loggingx.web.agent.dto;

import com.loggingx.dao.agent.CollectConfig;
import com.loggingx.dao.monitor.MonitorConfig;

import java.util.List;

/**
 * A store's collect config as edited on the page; values are validated by {@code AgentOptions.validate} and
 * {@code MonitorService.saveConfig}.
 *
 * @param monitor host resource monitoring, or {@code null} to keep it unchanged
 */
public record CollectConfigRequest(
        List<String> paths,
        List<String> windowsPaths,
        Boolean containers,
        String multiline,
        String multilinePattern,
        String transport,
        String kafkaServers,
        String kafkaTopic,
        Monitor monitor
) {

    /**
     * @param intervalSeconds seconds between scrapes
     * @param processes       whether busy processes are reported
     * @param containers      whether containers and services are reported (Linux)
     */
    public record Monitor(Boolean enabled, Integer intervalSeconds, Boolean processes, Boolean containers) {

        public MonitorConfig toConfig(Long storeId) {
            MonitorConfig defaults = MonitorConfig.defaults(storeId);
            return new MonitorConfig(storeId, enabled == null ? defaults.enabled() : enabled,
                    intervalSeconds == null ? defaults.intervalSeconds() : intervalSeconds,
                    processes == null ? defaults.processes() : processes,
                    containers == null ? defaults.containers() : containers, null);
        }
    }

    public CollectConfig toConfig(Long storeId) {
        return new CollectConfig(storeId, paths, windowsPaths, containers == null || containers, multiline,
                multilinePattern, transport, kafkaServers, kafkaTopic, null);
    }
}
