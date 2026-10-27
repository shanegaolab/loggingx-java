package com.loggingx.dao.post;

import com.loggingx.dao.support.EntityRepository;

/**
 * Post codes are unique; {@link #insert} and {@link #update} refuse a code that is already taken.
 */
public interface PostRepository extends EntityRepository<Post> {

    /**
     * @return whether any post is restricted to the department
     */
    boolean existsForDept(Long deptId);
}
