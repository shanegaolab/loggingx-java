package com.loggingx.dao.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Thread-safe in-memory store. Every operation is synchronized so unique-key checks and writes are atomic.
 */
public abstract class AbstractInMemoryRepository<T> implements EntityRepository<T> {

    private final Map<Long, T> entities = new LinkedHashMap<>();
    private long sequence;

    protected abstract Long idOf(T entity);

    protected abstract T withId(T entity, Long id);

    /**
     * @return the value that must be unique across entities, or {@code null} when the entity has none
     */
    protected String uniqueKeyOf(T entity) {
        return null;
    }

    @Override
    public synchronized List<T> findAll() {
        return List.copyOf(entities.values());
    }

    @Override
    public synchronized Optional<T> findById(Long id) {
        return Optional.ofNullable(entities.get(id));
    }

    @Override
    public synchronized Optional<T> insert(T entity) {
        if (isUniqueKeyTaken(entity, null)) {
            return Optional.empty();
        }
        T saved = withId(entity, ++sequence);
        entities.put(idOf(saved), saved);
        return Optional.of(saved);
    }

    @Override
    public synchronized boolean update(T entity) {
        Long id = idOf(entity);
        if (!entities.containsKey(id) || isUniqueKeyTaken(entity, id)) {
            return false;
        }
        entities.put(id, entity);
        return true;
    }

    @Override
    public synchronized void deleteById(Long id) {
        entities.remove(id);
    }

    protected synchronized Optional<T> findFirst(Predicate<T> predicate) {
        return entities.values().stream().filter(predicate).findFirst();
    }

    private boolean isUniqueKeyTaken(T entity, Long selfId) {
        String key = uniqueKeyOf(entity);
        return key != null && entities.values().stream()
                .anyMatch(other -> !Objects.equals(idOf(other), selfId) && key.equals(uniqueKeyOf(other)));
    }
}
