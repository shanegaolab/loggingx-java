package com.loggingx.dao.notify;

/**
 * How an alert rule notifies.
 *
 * @param ruleType       {@code host} or {@code log}
 * @param channels       comma-separated {@code email} and {@code sms}
 * @param userIds        JSON array of system user ids
 * @param groupIds       JSON array of notification group ids
 * @param notifyResolved whether a resolved alert is notified too
 * @param repeatMinutes  minutes between reminders while the alert fires; 0 for none
 */
public record AlertNotify(String ruleType, Long ruleId, String channels, String userIds, String groupIds,
                          boolean notifyResolved, int repeatMinutes) {
}
