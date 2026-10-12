package com.loggingx.web.menu.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loggingx.biz.menu.MenuNode;
import com.loggingx.dao.menu.Menu;

import java.util.List;

/**
 * A menu in the frontend's {@code AppRouteRecord} shape. {@code children} is omitted for leaf pages because the
 * frontend treats any route that has a {@code children} property as a directory.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MenuRouteResponse(
        Long id,
        Long parentId,
        String path,
        String name,
        String component,
        MenuMetaResponse meta,
        List<MenuRouteResponse> children
) {

    public static MenuRouteResponse from(MenuNode node) {
        Menu menu = node.menu();
        List<MenuRouteResponse> children = node.children().isEmpty()
                ? null
                : node.children().stream().map(MenuRouteResponse::from).toList();
        return new MenuRouteResponse(menu.id(), menu.parentId(), menu.path(), menu.name(), menu.component(),
                MenuMetaResponse.from(menu, node.buttons()), children);
    }
}
