package com.loggingx.biz.menu;

import com.loggingx.dao.menu.MenuType;

/**
 * Fields used to create or update a menu or button. {@code type} and {@code parentId} are ignored on update.
 */
public record MenuCommand(
        MenuType type,
        Long parentId,
        String title,
        String name,
        String path,
        String component,
        String icon,
        int sort,
        boolean enabled,
        boolean keepAlive,
        boolean hidden,
        boolean hideTab,
        String link,
        boolean iframe,
        boolean showBadge,
        String textBadge,
        boolean fixedTab,
        String activePath,
        boolean fullPage,
        String authMark
) {
}
