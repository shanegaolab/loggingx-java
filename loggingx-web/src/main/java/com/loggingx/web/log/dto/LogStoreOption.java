package com.loggingx.web.log.dto;

import com.loggingx.dao.log.LogStore;

/**
 * A log store for selection lists, without its ingest token.
 */
public record LogStoreOption(Long storeId, String code, String name, boolean enabled) {

    public static LogStoreOption from(LogStore store) {
        return new LogStoreOption(store.id(), store.code(), store.name(), store.enabled());
    }
}
