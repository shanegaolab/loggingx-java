package com.loggingx.dao.dept;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A department; departments form a tree through {@code parentId}.
 *
 * @param parentId {@link #ROOT_PARENT_ID} for top-level departments
 * @param name     unique among the departments with the same parent
 */
public record Dept(
        Long id,
        Long parentId,
        String name,
        int sort,
        String leader,
        String phone,
        String email,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    public static final long ROOT_PARENT_ID = 0L;

    public Dept {
        parentId = parentId == null ? ROOT_PARENT_ID : parentId;
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    public Dept withId(Long newId) {
        return new Dept(newId, parentId, name, sort, leader, phone, email, enabled, createdAt, updatedAt);
    }
}
