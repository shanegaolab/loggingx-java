package com.loggingx.biz.post;

import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.support.TestDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostServiceTest {

    private final TestDatabase database = new TestDatabase();
    private final PostService service = new PostService(database.postRepository);

    @Test
    void pageFiltersAndOrdersBySort() {
        service.create(new PostCommand("DEV", "Developer", 2, true, null));
        service.create(new PostCommand("CEO", "Chief executive", 1, true, null));
        service.create(new PostCommand("OPS", "Operator", 3, false, null));

        PageResult<Post> all = service.page(new PostQuery(1, 10, null, null, null));
        assertEquals(List.of("CEO", "DEV", "OPS"), all.records().stream().map(Post::code).toList());

        PageResult<Post> enabled = service.page(new PostQuery(1, 10, null, "e", true));
        assertEquals(List.of("CEO", "DEV"), enabled.records().stream().map(Post::code).toList());
    }

    @Test
    void codesMustBeUnique() {
        service.create(new PostCommand("DEV", "Developer", 1, true, null));
        Post other = service.create(new PostCommand("OPS", "Operator", 2, true, null));

        assertThrows(ConflictException.class, () -> service.create(new PostCommand("DEV", "Again", 3, true, null)));
        assertThrows(ConflictException.class,
                () -> service.update(other.id(), new PostCommand("DEV", "Operator", 2, true, null), "super"));
    }

    @Test
    void updateTrimsAndClearsBlankRemark() {
        Post post = service.create(new PostCommand(" PM ", " Manager ", 1, true, "lead"));

        Post updated = service.update(post.id(), new PostCommand("PM", "Manager", 5, false, "  "), "super");

        assertEquals("PM", post.code());
        assertEquals("Manager", post.name());
        assertNull(updated.remark());
        assertEquals(updated, database.postRepository.findById(post.id()).orElseThrow());
    }
}
