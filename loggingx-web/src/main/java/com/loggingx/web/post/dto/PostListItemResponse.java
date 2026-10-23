package com.loggingx.web.post.dto;

import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.post.Post;

public record PostListItemResponse(
        Long postId,
        String postCode,
        String postName,
        int sort,
        boolean enabled,
        String remark,
        String createTime
) {

    public static PostListItemResponse from(Post post) {
        return new PostListItemResponse(post.id(), post.code(), post.name(), post.sort(), post.enabled(),
                post.remark(), DateTimes.format(post.createdAt()));
    }
}
