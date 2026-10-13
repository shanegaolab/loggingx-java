package com.loggingx.dao.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

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
    public synchronized UpdateResult update(T expected, T updated) {
        Long id = idOf(updated);
        if (!Objects.equals(idOf(expected), id)) {
            throw new IllegalArgumentException("expected and updated must have the same id");
        }
        T current = entities.get(id);
        if (current == null) {
            return UpdateResult.NOT_FOUND;
        }
        if (!current.equals(expected)) {
            return UpdateResult.STALE;
        }
        return store(id, updated);
    }

    @Override
    public synchronized UpdateResult modify(Long id, UnaryOperator<T> change) {
        T current = entities.get(id);
        if (current == null) {
            return UpdateResult.NOT_FOUND;
        }
        T updated = change.apply(current);
        if (!Objects.equals(idOf(updated), id)) {
            throw new IllegalArgumentException("change must keep the entity id");
        }
        return store(id, updated);
    }

    private UpdateResult store(Long id, T updated) {
        if (isUniqueKeyTaken(updated, id)) {
            return UpdateResult.DUPLICATE_KEY;
        }
        entities.put(id, updated);
        return UpdateResult.UPDATED;
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
