package com.loggingx.dao.notify;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A notification sent, or that failed.
 *
 * @param eventType {@code host}, {@code log} or {@code test}
 * @param kind      {@code firing}, {@code resolved}, {@code repeat} or {@code test}
 * @param channel   {@code email} or {@code sms}
 * @param status    {@code success} or {@code failed}
 */
public record NotifyRecord(Long id, String eventType, Long eventId, String ruleName, String kind, String channel,
                           String recipient, String status, String error, Instant sentAt) {

    public NotifyRecord {
        sentAt = Timestamps.storable(sentAt);
    }
}
