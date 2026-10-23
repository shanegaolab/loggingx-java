package com.loggingx.biz.post;

/**
 * Post list filters; text filters match case-insensitively by substring, blank values are ignored.
 */
public record PostQuery(Integer current, Integer size, String postCode, String postName, Boolean enabled) {
}
