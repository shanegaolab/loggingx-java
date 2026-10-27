package com.loggingx.dao.post;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class MybatisPostRepository extends AbstractMybatisRepository<Post> implements PostRepository {

    private final PostMapper mapper;

    public MybatisPostRepository(PostMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
    }

    @Override
    public boolean existsForDept(Long deptId) {
        return mapper.existsForDept(deptId);
    }

    @Override
    protected Long idOf(Post post) {
        return post.id();
    }

    @Override
    protected Post withId(Post post, Long id) {
        return post.withId(id);
    }

    @Override
    protected List<Post> loadAll() {
        return attachLinks(mapper.selectAll(), true, mapper::selectDeptLinks, Post::withDeptIds);
    }

    @Override
    protected Optional<Post> load(Long id, boolean forUpdate) {
        return Optional.ofNullable(mapper.selectById(id, forUpdate)).map(found ->
                attachLinks(List.of(found), false, mapper::selectDeptLinks, Post::withDeptIds).getFirst());
    }

    @Override
    protected Long insertRow(Post post) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(post, key);
        syncLinks(key.getId(), Set.of(), post.deptIds(), mapper::deleteDeptLinks, mapper::insertDeptLinks);
        return key.getId();
    }

    @Override
    protected void updateRow(Post current, Post updated) {
        mapper.update(updated);
        syncLinks(updated.id(), current.deptIds(), updated.deptIds(), mapper::deleteDeptLinks,
                mapper::insertDeptLinks);
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }
}
