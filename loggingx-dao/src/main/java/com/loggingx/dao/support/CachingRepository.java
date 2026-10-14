package com.loggingx.dao.support;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Serves reads of a small table from a {@link TableCache} and evicts it after every write. Writes, including the
 * row lock and staleness check of {@link #update}, go to the database repository.
 * <p>
 * Eviction also follows writes that fail as stale or not found, since those suggest the cache lags behind.
 */
public abstract class CachingRepository<T> implements EntityRepository<T> {

    private final EntityRepository<T> delegate;
    private final TableCache<T> cache;

    protected CachingRepository(EntityRepository<T> delegate, TableCache<T> cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    protected abstract Long idOf(T entity);

    @Override
    public List<T> findAll() {
        return cache.get(delegate::findAll);
    }

    @Override
    public Optional<T> findById(Long id) {
        return findFirst(entity -> Objects.equals(idOf(entity), id));
    }

    @Override
    public Optional<T> insert(T entity) {
        try {
            return delegate.insert(entity);
        } finally {
            cache.evict();
        }
    }

    @Override
    public UpdateResult update(T expected, T updated) {
        try {
            return delegate.update(expected, updated);
        } finally {
            cache.evict();
        }
    }

    @Override
    public UpdateResult modify(Long id, UnaryOperator<T> change) {
        try {
            return delegate.modify(id, change);
        } finally {
            cache.evict();
        }
    }

    @Override
    public void deleteById(Long id) {
        try {
            delegate.deleteById(id);
        } finally {
            cache.evict();
        }
    }

    protected Optional<T> findFirst(Predicate<T> predicate) {
        return findAll().stream().filter(predicate).findFirst();
    }
}
