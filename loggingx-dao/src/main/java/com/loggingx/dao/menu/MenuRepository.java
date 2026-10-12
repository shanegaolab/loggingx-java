package com.loggingx.dao.menu;

import com.loggingx.dao.support.EntityRepository;

/**
 * Route names of {@link MenuType#MENU} entries are unique; {@link #insert} and {@link #update} refuse a taken name.
 */
public interface MenuRepository extends EntityRepository<Menu> {
}
