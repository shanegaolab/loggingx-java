package com.loggingx.dao.support;

public enum UpdateResult {
    UPDATED,
    /** No entity has the id. */
    NOT_FOUND,
    /** The stored entity changed since it was read, so the update was based on stale data. */
    STALE,
    /** Another entity already uses the unique key. */
    DUPLICATE_KEY
}
