package com.loggingx.dao.dept;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisDeptRepository extends AbstractMybatisRepository<Dept> implements DeptRepository {

    private final DeptMapper mapper;

    public MybatisDeptRepository(DeptMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
    }

    @Override
    protected Long idOf(Dept dept) {
        return dept.id();
    }

    @Override
    protected Dept withId(Dept dept, Long id) {
        return dept.withId(id);
    }

    @Override
    protected List<Dept> loadAll() {
        return mapper.selectAll();
    }

    @Override
    protected Optional<Dept> load(Long id, boolean forUpdate) {
        return Optional.ofNullable(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(Dept dept) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(dept, key);
        return key.getId();
    }

    @Override
    protected void updateRow(Dept current, Dept updated) {
        mapper.update(updated);
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }
}
