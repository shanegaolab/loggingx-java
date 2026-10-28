package com.loggingx.biz.monitor;

import com.loggingx.dao.log.LogSearch;

/**
 * Published when a log alert starts firing.
 *
 * @param search the rule's search over the window that fired
 */
public record LogAlertFired(Long storeId, Long eventId, Long ruleId, String ruleName, LogSearch search) {
}
