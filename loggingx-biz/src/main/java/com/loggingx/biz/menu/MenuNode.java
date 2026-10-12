package com.loggingx.biz.menu;

import com.loggingx.dao.menu.Menu;

import java.util.List;

/**
 * A menu with its child menus and its buttons, both sorted.
 */
public record MenuNode(Menu menu, List<MenuNode> children, List<Menu> buttons) {
}
