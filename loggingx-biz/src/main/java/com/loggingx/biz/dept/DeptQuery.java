package com.loggingx.biz.dept;

/**
 * Department tree filters; the name matches case-insensitively by substring, blank values are ignored.
 */
public record DeptQuery(String deptName, Boolean enabled) {
}
