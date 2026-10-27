package com.loggingx.biz.post;

import com.loggingx.biz.support.Filters;
import com.loggingx.biz.support.Updates;
import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.dept.DeptRepository;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.post.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Post (job position) administration. A post may be restricted to departments, in which case only users of those
 * departments or their descendants can hold it. A post still held by users cannot be deleted. Narrowing a post's
 * departments does not take it away from users who already hold it.
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final String POST_NOT_FOUND = "Post was not found";
    private static final Comparator<Post> ORDER = Comparator.comparingInt(Post::sort).thenComparing(Post::id);
    private static final Comparator<Dept> DEPT_ORDER = Comparator.comparingInt(Dept::sort).thenComparing(Dept::id);

    private final PostRepository postRepository;
    private final UserAccountRepository userAccountRepository;
    private final DeptRepository deptRepository;

    public PostService(PostRepository postRepository, UserAccountRepository userAccountRepository,
                       DeptRepository deptRepository) {
        this.postRepository = postRepository;
        this.userAccountRepository = userAccountRepository;
        this.deptRepository = deptRepository;
    }

    /**
     * @return the matching posts ordered by sort value
     */
    public PageResult<PostView> page(PostQuery query) {
        List<Post> matches = postRepository.findAll().stream()
                .filter(post -> Filters.containsIgnoreCase(post.code(), query.postCode()))
                .filter(post -> Filters.containsIgnoreCase(post.name(), query.postName()))
                .filter(post -> Filters.equalsIfPresent(post.enabled(), query.enabled()))
                .sorted(ORDER)
                .toList();
        Map<Long, Dept> depts = deptsById();
        return PageResult.of(matches, query.current(), query.size()).map(post -> toView(post, depts));
    }

    /**
     * @return every post ordered by sort value, including disabled ones so that a user's current posts can still
     * be shown
     */
    public List<PostView> all() {
        Map<Long, Dept> depts = deptsById();
        return postRepository.findAll().stream().sorted(ORDER).map(post -> toView(post, depts)).toList();
    }

    public PostView create(PostCommand command) {
        Instant now = Instant.now();
        Post post = toPost(null, command, resolveDeptIds(command.deptIds()), now, now);
        Post created = postRepository.insert(post).orElseThrow(() -> duplicateCode(post.code()));
        log.info("event=post.create.success postId={} postCode={} deptIds={}",
                created.id(), created.code(), created.deptIds());
        return toView(created, deptsById());
    }

    public PostView update(Long id, PostCommand command, String operatorUsername) {
        Post existing = requirePost(id);
        Post updated = toPost(id, command, resolveDeptIds(command.deptIds()), existing.createdAt(), Instant.now());
        Updates.require(postRepository.update(existing, updated), POST_NOT_FOUND, () -> duplicateCode(updated.code()));
        log.info("event=post.update.success operator={} postId={} postCode={} enabled={} deptIds={}",
                operatorUsername, updated.id(), updated.code(), updated.enabled(), updated.deptIds());
        return toView(updated, deptsById());
    }

    public void delete(Long id, String operatorUsername) {
        Post post = requirePost(id);
        if (userAccountRepository.existsWithPost(id)) {
            throw new BadRequestException("Remove the post from its users first");
        }
        postRepository.deleteById(id);
        log.info("event=post.delete.success operator={} postId={} postCode={}", operatorUsername, id, post.code());
    }

    private Post requirePost(Long id) {
        return postRepository.findById(id).orElseThrow(() -> new NotFoundException(POST_NOT_FOUND));
    }

    private Set<Long> resolveDeptIds(List<Long> deptIds) {
        Set<Long> resolved = new HashSet<>();
        for (Long deptId : deptIds == null ? List.<Long>of() : deptIds) {
            if (deptRepository.findById(deptId).isEmpty()) {
                throw new BadRequestException("Department was not found");
            }
            resolved.add(deptId);
        }
        return resolved;
    }

    private Map<Long, Dept> deptsById() {
        return deptRepository.findAll().stream().collect(Collectors.toMap(Dept::id, Function.identity()));
    }

    private static PostView toView(Post post, Map<Long, Dept> depts) {
        List<Dept> postDepts = post.deptIds().stream()
                .map(depts::get)
                .filter(Objects::nonNull)
                .sorted(DEPT_ORDER)
                .toList();
        return new PostView(post, postDepts.stream().map(Dept::id).toList(),
                postDepts.stream().map(Dept::name).toList());
    }

    private static Post toPost(Long id, PostCommand command, Set<Long> deptIds, Instant createdAt,
                               Instant updatedAt) {
        String remark = command.remark() == null || command.remark().isBlank() ? null : command.remark().trim();
        return new Post(id, command.code().trim(), command.name().trim(), command.sort(), command.enabled(), remark,
                deptIds, createdAt, updatedAt);
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("Post code '" + code + "' already exists");
    }
}
