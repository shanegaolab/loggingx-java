package com.loggingx.web.log.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.loggingx.biz.log.LogStoreCommand;
import com.loggingx.biz.log.LogStoreService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create or update a log store. {@code code} is only read on create, and checked by {@link LogStoreService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogStoreRequest(
        @Size(max = 64, message = "code must be at most 64 characters")
        String code,
        @NotBlank(message = "name must not be blank")
        @Size(max = 64, message = "name must be at most 64 characters")
        String name,
        @Size(max = 255, message = "description must be at most 255 characters")
        String description,
        @NotNull(message = "retentionDays is required")
        @Min(value = LogStoreService.MIN_RETENTION_DAYS, message = "retentionDays must be between 1 and 365")
        @Max(value = LogStoreService.MAX_RETENTION_DAYS, message = "retentionDays must be between 1 and 365")
        Integer retentionDays,
        boolean enabled,
        Backup backup
) {

    /**
     * The store's archiving, set together with the store; {@code null} leaves it unchanged.
     */
    public record Backup(Boolean enabled, Integer archiveAfterDays, Integer keepDays) {
    }

    public LogStoreCommand toCommand() {
        return new LogStoreCommand(code, name, description, retentionDays, enabled);
    }
}
