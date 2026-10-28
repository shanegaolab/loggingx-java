package com.loggingx.dao.ai;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * An AI analysis of an error.
 *
 * @param triggerType  {@code auto} (a new kind of error), {@code alert} (a log alert fired) or {@code manual}
 * @param status       {@code pending}, {@code running}, {@code success} or {@code failed}
 * @param reviewState  {@code open}, {@code resolved} or {@code ignored}, set by people
 * @param codeRefs     JSON array of the code sent to the model: path, start line, focus line, text
 * @param suggestion   how to fix it, Markdown
 * @param diff         a unified diff of the change, or {@code null}
 * @param confidence   {@code high}, {@code medium} or {@code low}, as the model judges
 */
public record AiDiagnosis(Long id, Long storeId, Long groupId, String triggerType, Long alertEventId, String status,
                          String reviewState, String errorType, String logText, String host, String source,
                          Instant logTime, String repoCommit, String codeRefs, String provider, String model,
                          String summary, String rootCause, String suggestion, String diff, String confidence,
                          Integer promptTokens, Integer completionTokens, String error, String createdBy,
                          Instant createdAt, Instant finishedAt) {

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";

    public AiDiagnosis {
        logTime = Timestamps.storable(logTime);
        createdAt = Timestamps.storable(createdAt);
        finishedAt = Timestamps.storable(finishedAt);
    }

    public AiDiagnosis withId(Long newId) {
        return new AiDiagnosis(newId, storeId, groupId, triggerType, alertEventId, status, reviewState, errorType,
                logText, host, source, logTime, repoCommit, codeRefs, provider, model, summary, rootCause,
                suggestion, diff, confidence, promptTokens, completionTokens, error, createdBy, createdAt,
                finishedAt);
    }
}
