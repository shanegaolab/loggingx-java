package com.loggingx.web.post.dto;

import com.loggingx.biz.post.PostView;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.post.Post;

import java.util.List;

/**
 * @param deptIds   the departments the post applies to, together with their descendants; empty for every
 *                  department
 * @param deptNames in the same order as {@code deptIds}
 */
public record PostListItemResponse(
        Long postId,
        String postCode,
        String postName,
        int sort,
        boolean enabled,
        String remark,
        List<Long> deptIds,
        List<String> deptNames,
        String createTime
) {

    public static PostListItemResponse from(PostView view) {
        Post post = view.post();
        return new PostListItemResponse(post.id(), post.code(), post.name(), post.sort(), post.enabled(),
                post.remark(), view.deptIds(), view.deptNames(), DateTimes.format(post.createdAt()));
    }
}
