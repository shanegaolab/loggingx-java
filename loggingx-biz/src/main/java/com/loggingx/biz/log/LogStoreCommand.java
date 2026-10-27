package com.loggingx.biz.log;

/**
 * @param code ignored on update: producers address the store by it, so it cannot change
 */
public record LogStoreCommand(String code, String name, String description, int retentionDays, boolean enabled) {
}
