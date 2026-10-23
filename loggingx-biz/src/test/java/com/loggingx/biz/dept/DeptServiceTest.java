package com.loggingx.biz.dept;

import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.support.TestDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeptServiceTest {

    private final TestDatabase database = new TestDatabase();
    private final DeptService service = new DeptService(database.deptRepository);

    private Long create(Long parentId, String name, int sort) {
        return service.create(new DeptCommand(parentId, name, sort, null, null, null, true)).id();
    }

    private static List<String> names(List<DeptNode> nodes) {
        return nodes.stream().map(node -> node.dept().name()).toList();
    }

    @Test
    void treeNestsChildrenOrderedBySort() {
        Long company = create(null, "Company", 1);
        create(company, "Ops", 2);
        Long rnd = create(company, "R&D", 1);
        create(rnd, "Backend", 1);

        List<DeptNode> tree = service.tree(new DeptQuery(null, null));

        assertEquals(List.of("Company"), names(tree));
        assertEquals(List.of("R&D", "Ops"), names(tree.getFirst().children()));
        assertEquals(List.of("Backend"), names(tree.getFirst().children().getFirst().children()));
    }

    @Test
    void filteredTreeKeepsAncestorsOfMatches() {
        Long company = create(null, "Company", 1);
        Long rnd = create(company, "R&D", 1);
        create(rnd, "Backend", 1);
        create(company, "Ops", 2);

        List<DeptNode> tree = service.tree(new DeptQuery("back", null));

        assertEquals(List.of("Company"), names(tree));
        assertEquals(List.of("R&D"), names(tree.getFirst().children()));
        assertEquals(List.of("Backend"), names(tree.getFirst().children().getFirst().children()));
        assertTrue(service.tree(new DeptQuery("missing", null)).isEmpty());
    }

    @Test
    void siblingNamesMustBeUnique() {
        Long company = create(null, "Company", 1);
        Long rnd = create(company, "R&D", 1);
        create(rnd, "Team", 1);

        assertThrows(ConflictException.class, () -> create(company, "R&D", 2));
        // The same name is fine under another parent.
        create(company, "Team", 3);
    }

    @Test
    void departmentCannotMoveBelowItself() {
        Long company = create(null, "Company", 1);
        Long rnd = create(company, "R&D", 1);
        Long backend = create(rnd, "Backend", 1);

        assertThrows(BadRequestException.class, () -> service.update(rnd,
                new DeptCommand(backend, "R&D", 1, null, null, null, true), "super"));
        assertThrows(BadRequestException.class, () -> service.update(rnd,
                new DeptCommand(rnd, "R&D", 1, null, null, null, true), "super"));

        Dept moved = service.update(backend, new DeptCommand(company, "Backend", 1, null, null, null, true), "super");
        assertEquals(company, moved.parentId());
    }

    @Test
    void departmentWithChildrenCannotBeDeleted() {
        Long company = create(null, "Company", 1);
        Long rnd = create(company, "R&D", 1);

        assertThrows(BadRequestException.class, () -> service.delete(company, "super"));

        service.delete(rnd, "super");
        service.delete(company, "super");
        assertTrue(service.tree(new DeptQuery(null, null)).isEmpty());
    }

    @Test
    void parentMustExist() {
        assertThrows(BadRequestException.class, () -> create(42L, "Orphan", 1));
    }
}
