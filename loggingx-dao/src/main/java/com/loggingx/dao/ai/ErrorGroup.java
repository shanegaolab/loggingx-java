package com.loggingx.dao.ai;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * The occurrences of one kind of error in a store.
 *
 * @param fingerprint SHA-1 of the error type and first application frame, or of the normalized message
 * @param location    the first application frame, e.g. {@code com.shop.OrderService.submit(OrderService.java:88)}
 * @param sample      the newest occurrence's text
 * @param status      {@code open}, {@code resolved} or {@code ignored}
 */
public record ErrorGroup(Long id, Long storeId, String fingerprint, String errorType, String location, String sample,
                         String host, long occurrences, Instant firstSeen, Instant lastSeen, String status,
                         Long diagnosisId) {

    public ErrorGroup {
        firstSeen = Timestamps.storable(firstSeen);
        lastSeen = Timestamps.storable(lastSeen);
    }
}
