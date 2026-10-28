package com.loggingx.dao.ai;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * An operations document the AI assistant answers from: a runbook, a postmortem, an FAQ.
 *
 * @param content Markdown or plain text
 * @param tags    comma-separated
 * @param storeId the store it is about, or {@code null} for every store
 */
public record AiKnowledge(Long id, String title, String content, String tags, Long storeId, String createdBy,
                          Instant createdAt, Instant updatedAt) {

    public AiKnowledge {
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }
}
