package com.loggingx.dao.log;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisLogStoreRepository extends AbstractMybatisRepository<LogStore> implements LogStoreRepository {

    private final LogStoreMapper mapper;

    public MybatisLogStoreRepository(LogStoreMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
    }

    @Override
    public Optional<LogStore> findByCode(String code) {
        return Optional.ofNullable(mapper.selectByCode(code));
    }

    @Override
    public Optional<LogStore> findByIngestToken(String ingestToken) {
        return Optional.ofNullable(mapper.selectByIngestToken(ingestToken));
    }

    @Override
    protected Long idOf(LogStore store) {
        return store.id();
    }

    @Override
    protected LogStore withId(LogStore store, Long id) {
        return store.withId(id);
    }

    @Override
    protected List<LogStore> loadAll() {
        return mapper.selectAll();
    }

    @Override
    protected Optional<LogStore> load(Long id, boolean forUpdate) {
        return Optional.ofNullable(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(LogStore store) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(store, key);
        return key.getId();
    }

    @Override
    protected void updateRow(LogStore current, LogStore updated) {
        mapper.update(updated);
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }
}
