package com.loggingx.dao.dept;

import com.loggingx.dao.support.EntityRepository;

/**
 * Sibling departments have distinct names; {@link #insert} and {@link #update} refuse a name already taken under
 * the same parent.
 */
public interface DeptRepository extends EntityRepository<Dept> {
}
