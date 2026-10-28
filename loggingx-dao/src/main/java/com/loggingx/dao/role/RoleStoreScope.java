package com.loggingx.dao.role;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * The log stores a role may access.
 *
 * @param allStores every store, including stores created later
 * @param storeIds  JSON array of store ids, used when {@code allStores} is false
 */
public record RoleStoreScope(Long roleId, boolean allStores, String storeIds, Instant updatedAt) {

    public RoleStoreScope {
        updatedAt = Timestamps.storable(updatedAt);
    }
}
