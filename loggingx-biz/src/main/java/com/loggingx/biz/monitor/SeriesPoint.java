package com.loggingx.biz.monitor;

import java.time.Instant;

/**
 * A host's resource values over one bucket of a trend chart. Rates are averages over the bucket; {@code null} when
 * not reported or not computable (the first bucket after a gap or a reboot).
 */
public record SeriesPoint(
        Instant time,
        Double cpuPercent,
        Double memPercent,
        Double swapPercent,
        Double load1,
        Double load5,
        Double load15,
        Double diskReadBps,
        Double diskWriteBps,
        Double netRxBps,
        Double netTxBps,
        Double diskMaxPercent
) {
}
