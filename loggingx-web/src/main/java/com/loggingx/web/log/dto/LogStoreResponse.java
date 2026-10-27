package com.loggingx.web.log.dto;

import com.loggingx.biz.backup.BackupService.PolicyView;
import com.loggingx.biz.log.LogStoreView;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.agent.AgentCount;
import com.loggingx.dao.backup.BackupPolicy;
import com.loggingx.dao.log.LogStore;

/**
 * A log store as its managers see it, including the ingest token.
 *
 * @param lastLogTime      time of the newest log, or {@code null} when there is none
 * @param recentCount      number of logs from the last 24 hours; {@code null} when not computed
 * @param agentCount       collection agents installed for the store; {@code null} when not computed
 * @param onlineAgentCount those of them that synced recently; {@code null} when not computed
 * @param backup           the store's archiving; {@code null} when not computed
 */
public record LogStoreResponse(
        Long storeId,
        String code,
        String name,
        String description,
        int retentionDays,
        String ingestToken,
        boolean enabled,
        String lastLogTime,
        Long recentCount,
        Long agentCount,
        Long onlineAgentCount,
        Backup backup,
        String createTime,
        String updateTime
) {

    /**
     * @param enabled          whether the store is archived ({@code false} without a policy)
     * @param archiveAfterDays days after which logs are archived, or {@code null} without a policy
     * @param keepDays         days archives are kept, or {@code null} without a policy
     * @param archives         successful archives (days)
     * @param latestDay        the newest archived day, or {@code null}
     * @param failed           archives that failed and are retried
     * @param pendingDays      days past the retention kept from deletion until they are archived
     */
    public record Backup(boolean enabled, Integer archiveAfterDays, Integer keepDays, long archives,
                         long archivedBytes, long rawBytes, String latestDay, long failed, int pendingDays) {

        public static Backup from(PolicyView view, int pendingDays) {
            BackupPolicy policy = view.policy();
            return new Backup(policy != null && policy.enabled(), policy == null ? null : policy.archiveAfterDays(),
                    policy == null ? null : policy.keepDays(), view.archives(), view.archivedBytes(),
                    view.rawBytes(), view.latestDay() == null ? null : view.latestDay().toString(), view.failed(),
                    pendingDays);
        }
    }

    public static LogStoreResponse from(LogStoreView view, AgentCount agents, Backup backup) {
        return from(view.store(), DateTimes.format(view.lastLogTime()), view.recentCount(),
                agents == null ? 0L : agents.total(), agents == null ? 0L : agents.online(), backup);
    }

    public static LogStoreResponse from(LogStore store) {
        return from(store, null, null, null, null, null);
    }

    private static LogStoreResponse from(LogStore store, String lastLogTime, Long recentCount, Long agentCount,
                                         Long onlineAgentCount, Backup backup) {
        return new LogStoreResponse(store.id(), store.code(), store.name(), store.description(),
                store.retentionDays(), store.ingestToken(), store.enabled(), lastLogTime, recentCount, agentCount,
                onlineAgentCount, backup, DateTimes.format(store.createdAt()), DateTimes.format(store.updatedAt()));
    }
}
