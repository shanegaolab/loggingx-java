package com.loggingx.dao.support;

import java.util.List;
import java.util.Optional;

/**
 * Basic persistence operations shared by all entities with a numeric id.
 */
public interface EntityRepository<T> {

    /**
     * @return all entities in insertion order
     */
    List<T> findAll();

    Optional<T> findById(Long id);

    /**
     * Assigns a new id and stores the entity.
     *
     * @return the stored entity, or empty when its unique key is already taken
     */
    Optional<T> insert(T entity);

    /**
     * Replaces the stored entity with the same id.
     *
     * @return {@code false} when the entity does not exist or its unique key is taken by another entity
     */
    boolean update(T entity);

    void deleteById(Long id);
}
