package com.loggingx.dao.menu;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisMenuRepository extends AbstractMybatisRepository<Menu> implements MenuRepository {

    private final MenuMapper mapper;

    public MybatisMenuRepository(MenuMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
    }

    @Override
    protected Long idOf(Menu menu) {
        return menu.id();
    }

    @Override
    protected Menu withId(Menu menu, Long id) {
        return menu.withId(id);
    }

    @Override
    protected List<Menu> loadAll() {
        return mapper.selectAll();
    }

    @Override
    protected Optional<Menu> load(Long id, boolean forUpdate) {
        return Optional.ofNullable(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(Menu menu) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(menu, key);
        return key.getId();
    }

    @Override
    protected void updateRow(Menu current, Menu updated) {
        mapper.update(updated);
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }
}
