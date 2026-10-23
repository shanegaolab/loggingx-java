package com.loggingx.biz.post;

import com.loggingx.biz.support.Filters;
import com.loggingx.biz.support.Updates;
import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.post.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Post (job position) administration.
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final String POST_NOT_FOUND = "Post was not found";

    private final PostRepository postRepository;

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    /**
     * @return the matching posts ordered by sort value
     */
    public PageResult<Post> page(PostQuery query) {
        List<Post> matches = postRepository.findAll().stream()
                .filter(post -> Filters.containsIgnoreCase(post.code(), query.postCode()))
                .filter(post -> Filters.containsIgnoreCase(post.name(), query.postName()))
                .filter(post -> Filters.equalsIfPresent(post.enabled(), query.enabled()))
                .sorted(Comparator.comparingInt(Post::sort).thenComparing(Post::id))
                .toList();
        return PageResult.of(matches, query.current(), query.size());
    }

    public Post create(PostCommand command) {
        Instant now = Instant.now();
        Post post = toPost(null, command, now, now);
        Post created = postRepository.insert(post).orElseThrow(() -> duplicateCode(post.code()));
        log.info("event=post.create.success postId={} postCode={}", created.id(), created.code());
        return created;
    }

    public Post update(Long id, PostCommand command, String operatorUsername) {
        Post existing = requirePost(id);
        Post updated = toPost(id, command, existing.createdAt(), Instant.now());
        Updates.require(postRepository.update(existing, updated), POST_NOT_FOUND, () -> duplicateCode(updated.code()));
        log.info("event=post.update.success operator={} postId={} postCode={} enabled={}",
                operatorUsername, updated.id(), updated.code(), updated.enabled());
        return updated;
    }

    public void delete(Long id, String operatorUsername) {
        Post post = requirePost(id);
        postRepository.deleteById(id);
        log.info("event=post.delete.success operator={} postId={} postCode={}", operatorUsername, id, post.code());
    }

    private Post requirePost(Long id) {
        return postRepository.findById(id).orElseThrow(() -> new NotFoundException(POST_NOT_FOUND));
    }

    private static Post toPost(Long id, PostCommand command, Instant createdAt, Instant updatedAt) {
        String remark = command.remark() == null || command.remark().isBlank() ? null : command.remark().trim();
        return new Post(id, command.code().trim(), command.name().trim(), command.sort(), command.enabled(), remark,
                createdAt, updatedAt);
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("Post code '" + code + "' already exists");
    }
}
