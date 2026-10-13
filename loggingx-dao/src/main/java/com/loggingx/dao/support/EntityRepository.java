package com.loggingx.dao.support;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
     * Replaces {@code expected} with {@code updated}, but only while the stored entity still equals
     * {@code expected}, so concurrent edits based on the same snapshot cannot silently overwrite each other.
     * Both must have the same id.
     */
    UpdateResult update(T expected, T updated);

    /**
     * Atomically applies {@code change} to the current entity with the given id and stores the result. Use it for
     * changes that do not depend on what the caller read earlier, such as cascading a deletion.
     */
    UpdateResult modify(Long id, UnaryOperator<T> change);

    void deleteById(Long id);
}
