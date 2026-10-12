package com.loggingx.biz.role;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Role list filters; text filters match case-insensitively by substring, blank values are ignored.
 *
 * @param startTime first creation day to include
 * @param endTime   last creation day to include
 */
public record RoleQuery(
        Integer current,
        Integer size,
        String roleName,
        String roleCode,
        String description,
        Boolean enabled,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startTime,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endTime
) {
}
