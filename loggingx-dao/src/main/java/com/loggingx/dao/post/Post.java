package com.loggingx.dao.post;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;
import java.util.Set;

/**
 * A job position, such as a team lead or an engineer.
 *
 * @param code    unique post code such as {@code CEO}
 * @param deptIds the departments the post applies to, together with their descendants; empty when it applies to
 *                every department
 */
public record Post(
        Long id,
        String code,
        String name,
        int sort,
        boolean enabled,
        String remark,
        Set<Long> deptIds,
        Instant createdAt,
        Instant updatedAt
) {

    public Post {
        deptIds = deptIds == null ? Set.of() : Set.copyOf(deptIds);
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    /**
     * Creates a post that applies to every department; the persistence layer maps rows with it and adds the
     * departments separately.
     */
    public Post(Long id, String code, String name, int sort, boolean enabled, String remark, Instant createdAt,
                Instant updatedAt) {
        this(id, code, name, sort, enabled, remark, Set.of(), createdAt, updatedAt);
    }

    public Post withId(Long newId) {
        return new Post(newId, code, name, sort, enabled, remark, deptIds, createdAt, updatedAt);
    }

    public Post withDeptIds(Set<Long> newDeptIds) {
        return new Post(id, code, name, sort, enabled, remark, newDeptIds, createdAt, updatedAt);
    }

    /**
     * @param deptAndAncestors a department and all of its ancestors
     * @return whether a user of that department may hold this post
     */
    public boolean appliesTo(Set<Long> deptAndAncestors) {
        return deptIds.isEmpty() || deptIds.stream().anyMatch(deptAndAncestors::contains);
    }
}
