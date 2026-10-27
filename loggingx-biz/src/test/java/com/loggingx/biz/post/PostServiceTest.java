package com.loggingx.biz.post;

import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.support.TestDatabase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostServiceTest {

    private final TestDatabase database = new TestDatabase();
    private final PostService service = new PostService(database.postRepository, database.userAccountRepository,
            database.deptRepository);

    private static PostCommand command(String code, String name, int sort, boolean enabled, String remark) {
        return new PostCommand(code, name, sort, enabled, remark, null);
    }

    private Long insertDept(String name, int sort) {
        Instant now = Instant.now();
        return database.deptRepository.insert(new Dept(null, Dept.ROOT_PARENT_ID, name, sort, null, null, null,
                true, now, now)).orElseThrow().id();
    }

    @Test
    void pageFiltersAndOrdersBySort() {
        service.create(command("DEV", "Developer", 2, true, null));
        service.create(command("CEO", "Chief executive", 1, true, null));
        service.create(command("OPS", "Operator", 3, false, null));

        PageResult<PostView> all = service.page(new PostQuery(1, 10, null, null, null));
        assertEquals(List.of("CEO", "DEV", "OPS"), all.records().stream().map(view -> view.post().code()).toList());

        PageResult<PostView> enabled = service.page(new PostQuery(1, 10, null, "e", true));
        assertEquals(List.of("CEO", "DEV"), enabled.records().stream().map(view -> view.post().code()).toList());
    }

    @Test
    void codesMustBeUnique() {
        service.create(command("DEV", "Developer", 1, true, null));
        Post other = service.create(command("OPS", "Operator", 2, true, null)).post();

        assertThrows(ConflictException.class, () -> service.create(command("DEV", "Again", 3, true, null)));
        assertThrows(ConflictException.class,
                () -> service.update(other.id(), command("DEV", "Operator", 2, true, null), "super"));
    }

    @Test
    void updateTrimsAndClearsBlankRemark() {
        Post post = service.create(command(" PM ", " Manager ", 1, true, "lead")).post();

        Post updated = service.update(post.id(), command("PM", "Manager", 5, false, "  "), "super").post();

        assertEquals("PM", post.code());
        assertEquals("Manager", post.name());
        assertNull(updated.remark());
        assertEquals(updated, database.postRepository.findById(post.id()).orElseThrow());
    }

    @Test
    void postsCanBeRestrictedToDepartments() {
        Long sales = insertDept("Sales", 2);
        Long rnd = insertDept("R&D", 1);

        PostView created = service.create(new PostCommand("DEV", "Developer", 1, true, null, List.of(sales, rnd)));

        assertEquals(Set.of(sales, rnd), created.post().deptIds());
        assertEquals(List.of(rnd, sales), created.deptIds());
        assertEquals(List.of("R&D", "Sales"), created.deptNames());
        assertEquals(Set.of(sales, rnd),
                database.postRepository.findById(created.post().id()).orElseThrow().deptIds());

        PostView widened = service.update(created.post().id(),
                new PostCommand("DEV", "Developer", 1, true, null, List.of()), "super");
        assertEquals(Set.of(), database.postRepository.findById(widened.post().id()).orElseThrow().deptIds());
    }

    @Test
    void restrictingToUnknownDepartmentIsRejected() {
        assertThrows(BadRequestException.class,
                () -> service.create(new PostCommand("DEV", "Developer", 1, true, null, List.of(99L))));
    }
}
