package com.loggingx.web.role.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @param menuIds ids of the menus and buttons to grant, including half-checked parent menus
 */
public record RoleMenusRequest(@NotNull(message = "menuIds must not be null") List<@NotNull Long> menuIds) {
}
