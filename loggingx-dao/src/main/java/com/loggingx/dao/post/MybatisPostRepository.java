package com.loggingx.dao.post;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisPostRepository extends AbstractMybatisRepository<Post> implements PostRepository {

    private final PostMapper mapper;

    public MybatisPostRepository(PostMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
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
        return mapper.selectAll();
    }

    @Override
    protected Optional<Post> load(Long id, boolean forUpdate) {
        return Optional.ofNullable(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(Post post) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(post, key);
        return key.getId();
    }

    @Override
    protected void updateRow(Post current, Post updated) {
        mapper.update(updated);
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }
}
