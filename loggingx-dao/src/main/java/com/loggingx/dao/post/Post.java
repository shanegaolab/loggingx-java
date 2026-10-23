package com.loggingx.dao.post;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A job position, such as a team lead or an engineer.
 *
 * @param code unique post code such as {@code CEO}
 */
public record Post(
        Long id,
        String code,
        String name,
        int sort,
        boolean enabled,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {

    public Post {
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    public Post withId(Long newId) {
        return new Post(newId, code, name, sort, enabled, remark, createdAt, updatedAt);
    }
}
