package com.loggingx.web.post;

import com.loggingx.biz.post.PostQuery;
import com.loggingx.biz.post.PostService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.api.PageResult;
import com.loggingx.web.post.dto.PostListItemResponse;
import com.loggingx.web.post.dto.PostRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post (job position) management. Every endpoint requires access to the post management menu.
 */
@RestController
@RequestMapping("/post")
@PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Post')")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/list")
    public ApiResponse<PageResult<PostListItemResponse>> list(PostQuery query) {
        return ApiResponse.success(postService.page(query).map(PostListItemResponse::from));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostListItemResponse> create(@Valid @RequestBody PostRequest request) {
        return ApiResponse.success(PostListItemResponse.from(postService.create(request.toCommand())));
    }

    @PutMapping("/{id}")
    public ApiResponse<PostListItemResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody PostRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(PostListItemResponse.from(
                postService.update(id, request.toCommand(), authentication.getName())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        postService.delete(id, authentication.getName());
        return ApiResponse.success(null);
    }
}
