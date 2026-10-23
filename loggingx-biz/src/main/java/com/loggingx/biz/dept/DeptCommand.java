package com.loggingx.biz.dept;

/**
 * @param parentId the parent department, or {@code null} or {@code 0} for a top-level department
 */
public record DeptCommand(Long parentId, String name, int sort, String leader, String phone, String email,
                          boolean enabled) {
}
