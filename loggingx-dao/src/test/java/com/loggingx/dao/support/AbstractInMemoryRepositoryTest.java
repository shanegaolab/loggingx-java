package com.loggingx.dao.support;

import com.loggingx.dao.role.InMemoryRoleRepository;
import com.loggingx.dao.role.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractInMemoryRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-10-13T00:00:00Z");

    private final InMemoryRoleRepository repository = new InMemoryRoleRepository();

    private Role insert(String code) {
        return repository.insert(new Role(null, code, code, null, true, Set.of(), CREATED, CREATED)).orElseThrow();
    }

    @Test
    void updateRefusesStaleSnapshot() {
        Role original = insert("R_A");
        Role first = original.withMenuIds(Set.of(1L), CREATED);
        Role second = original.withMenuIds(Set.of(2L), CREATED);

        assertEquals(UpdateResult.UPDATED, repository.update(original, first));
        assertEquals(UpdateResult.STALE, repository.update(original, second));
        assertEquals(Set.of(1L), repository.findById(original.id()).orElseThrow().menuIds());
    }

    @Test
    void updateReportsMissingEntityAndDuplicateKey() {
        Role a = insert("R_A");
        insert("R_B");
        Role renamed = new Role(a.id(), "R_B", "A", null, true, Set.of(), CREATED, CREATED);

        assertEquals(UpdateResult.DUPLICATE_KEY, repository.update(a, renamed));
        repository.deleteById(a.id());
        assertEquals(UpdateResult.NOT_FOUND, repository.update(a, a));
    }

    @Test
    void modifyAppliesTheChangeToTheCurrentEntity() {
        Role original = insert("R_A");
        repository.update(original, original.withMenuIds(Set.of(1L, 2L), CREATED));

        UpdateResult result = repository.modify(original.id(), current -> current.withMenuIds(
                current.menuIds().stream().filter(id -> id != 1L).collect(Collectors.toSet()),
                CREATED));

        assertEquals(UpdateResult.UPDATED, result);
        assertEquals(Set.of(2L), repository.findById(original.id()).orElseThrow().menuIds());
        assertEquals(UpdateResult.NOT_FOUND, repository.modify(99L, current -> current));
    }
}
