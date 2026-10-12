package com.loggingx.dao.menu;

import com.loggingx.dao.support.AbstractInMemoryRepository;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryMenuRepository extends AbstractInMemoryRepository<Menu> implements MenuRepository {

    @Override
    protected Long idOf(Menu menu) {
        return menu.id();
    }

    @Override
    protected Menu withId(Menu menu, Long id) {
        return menu.withId(id);
    }

    @Override
    protected String uniqueKeyOf(Menu menu) {
        return menu.isButton() ? null : menu.name();
    }
}
