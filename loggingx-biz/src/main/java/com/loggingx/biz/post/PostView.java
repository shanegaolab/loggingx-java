package com.loggingx.biz.post;

import com.loggingx.dao.post.Post;

import java.util.List;

/**
 * @param deptIds   the departments the post applies to, in department order; empty for every department
 * @param deptNames names of those departments, in the same order as {@code deptIds}
 */
public record PostView(Post post, List<Long> deptIds, List<String> deptNames) {
}
