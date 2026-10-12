package com.loggingx.dao.menu;

import java.time.Instant;

/**
 * A navigation route or a button permission. Buttons only use {@code parentId}, {@code title},
 * {@code authMark} and {@code sort}.
 *
 * @param parentId  {@link #ROOT_PARENT_ID} for top-level menus
 * @param name      unique route name such as {@code User}; {@code null} for buttons
 * @param component view path such as {@code /system/user}, or {@code /index/index} for a top-level directory
 */
public record Menu(
        Long id,
        Long parentId,
        MenuType type,
        String name,
        String path,
        String component,
        String title,
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
        String authMark,
        Instant updatedAt
) {

    public static final long ROOT_PARENT_ID = 0L;

    public Menu withId(Long newId) {
        return new Menu(newId, parentId, type, name, path, component, title, icon, sort, enabled, keepAlive, hidden,
                hideTab, link, iframe, showBadge, textBadge, fixedTab, activePath, fullPage, authMark, updatedAt);
    }

    public boolean isButton() {
        return type == MenuType.BUTTON;
    }
}
