package com.loggingx.dao.support;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Relational store backed by MyBatis mappers. Writes run in a transaction, and writes to an existing entity first
 * lock its row, so the staleness check of {@link #update} and the read-modify-write of {@link #modify} are atomic.
 * Reads run without one: a write racing a read at worst makes a later {@link #update} report
 * {@link UpdateResult#STALE}. Unique keys are enforced by database constraints.
 */
public abstract class AbstractMybatisRepository<T> implements EntityRepository<T> {

    private final TransactionTemplate transactions;

    protected AbstractMybatisRepository(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    protected abstract Long idOf(T entity);

    protected abstract T withId(T entity, Long id);

    /**
     * @return all entities ordered by id, with their associations
     */
    protected abstract List<T> loadAll();

    /**
     * Loads the entity with its associations.
     *
     * @param forUpdate whether to lock the entity's row until the transaction ends
     */
    protected abstract Optional<T> load(Long id, boolean forUpdate);

    /**
     * Inserts the entity, including its associations, ignoring its id.
     *
     * @return the generated id
     */
    protected abstract Long insertRow(T entity);

    /**
     * Overwrites the stored entity {@code current} with {@code updated}, including its associations.
     */
    protected abstract void updateRow(T current, T updated);

    /**
     * Deletes the entity; its associations go with it through {@code ON DELETE CASCADE}.
     */
    protected abstract void deleteRow(Long id);

    @Override
    public List<T> findAll() {
        return loadAll();
    }

    @Override
    public Optional<T> findById(Long id) {
        return load(id, false);
    }

    @Override
    public Optional<T> insert(T entity) {
        try {
            // Entities normalize their values on construction, so the stored row equals the entity with its new id.
            return Optional.of(transactions.execute(status -> withId(entity, insertRow(entity))));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public UpdateResult update(T expected, T updated) {
        Long id = idOf(updated);
        if (!Objects.equals(idOf(expected), id)) {
            throw new IllegalArgumentException("expected and updated must have the same id");
        }
        return store(id, current -> current.equals(expected) ? updated : null);
    }

    @Override
    public UpdateResult modify(Long id, UnaryOperator<T> change) {
        return store(id, current -> {
            T updated = change.apply(current);
            if (!Objects.equals(idOf(updated), id)) {
                throw new IllegalArgumentException("change must keep the entity id");
            }
            return updated;
        });
    }

    @Override
    public void deleteById(Long id) {
        deleteRow(id);
    }

    /**
     * Fills in an association of {@code entities} from its join table.
     *
     * @param all    whether {@code entities} are all entities, so the links need no filter
     * @param select reads the links of the given owner ids, or of every owner for {@code null}
     */
    protected List<T> attachLinks(List<T> entities, boolean all, Function<Collection<Long>, List<Link>> select,
                                  BiFunction<T, Set<Long>, T> attach) {
        if (entities.isEmpty()) {
            return entities;
        }
        Map<Long, Set<Long>> members = Link.group(select.apply(all ? null : entities.stream().map(this::idOf).toList()));
        return entities.stream()
                .map(entity -> attach.apply(entity, members.getOrDefault(idOf(entity), Set.of())))
                .toList();
    }

    protected <R> R inTransaction(Supplier<R> work) {
        return transactions.execute(status -> work.get());
    }

    /**
     * Writes only the join rows that differ between the {@code current} and {@code updated} members of an owner.
     */
    protected static void syncLinks(Long ownerId, Set<Long> current, Set<Long> updated,
                                    BiConsumer<Long, Collection<Long>> delete,
                                    BiConsumer<Long, Collection<Long>> insert) {
        List<Long> removed = current.stream().filter(id -> !updated.contains(id)).toList();
        List<Long> added = updated.stream().filter(id -> !current.contains(id)).toList();
        if (!removed.isEmpty()) {
            delete.accept(ownerId, removed);
        }
        if (!added.isEmpty()) {
            insert.accept(ownerId, added);
        }
    }

    /**
     * @param change maps the locked current entity to the one to store, or to {@code null} when it is stale
     */
    private UpdateResult store(Long id, UnaryOperator<T> change) {
        try {
            return inTransaction(() -> {
                Optional<T> current = load(id, true);
                if (current.isEmpty()) {
                    return UpdateResult.NOT_FOUND;
                }
                T updated = change.apply(current.get());
                if (updated == null) {
                    return UpdateResult.STALE;
                }
                updateRow(current.get(), updated);
                return UpdateResult.UPDATED;
            });
        } catch (DuplicateKeyException e) {
            return UpdateResult.DUPLICATE_KEY;
        }
    }
}
