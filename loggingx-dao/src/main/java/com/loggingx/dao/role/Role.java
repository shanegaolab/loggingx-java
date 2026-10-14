package com.loggingx.dao.role;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;
import java.util.Set;

/**
 * @param code    unique role code such as {@code R_SUPER}
 * @param menuIds ids of the menus and buttons this role may access
 */
public record Role(
        Long id,
        String code,
        String name,
        String description,
        boolean enabled,
        Set<Long> menuIds,
        Instant createdAt,
        Instant updatedAt
) {

    public Role {
        menuIds = menuIds == null ? Set.of() : Set.copyOf(menuIds);
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    /**
     * Creates a role without menus; the persistence layer maps rows with it and adds the menus separately.
     */
    public Role(Long id, String code, String name, String description, boolean enabled, Instant createdAt,
                Instant updatedAt) {
        this(id, code, name, description, enabled, Set.of(), createdAt, updatedAt);
    }

    public Role withId(Long newId) {
        return new Role(newId, code, name, description, enabled, menuIds, createdAt, updatedAt);
    }

    public Role withMenuIds(Set<Long> newMenuIds) {
        return new Role(id, code, name, description, enabled, newMenuIds, createdAt, updatedAt);
    }

    public Role withMenuIds(Set<Long> newMenuIds, Instant now) {
        return new Role(id, code, name, description, enabled, newMenuIds, createdAt, now);
    }
}
