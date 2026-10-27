package com.loggingx.biz.post;

import java.util.List;

/**
 * @param deptIds the departments the post applies to, together with their descendants; {@code null} or empty for
 *                every department
 */
public record PostCommand(String code, String name, int sort, boolean enabled, String remark, List<Long> deptIds) {
}
