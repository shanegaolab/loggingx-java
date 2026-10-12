package com.loggingx.web.menu;

import com.loggingx.biz.menu.MenuNode;
import com.loggingx.biz.menu.MenuService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.web.menu.dto.MenuRequest;
import com.loggingx.web.menu.dto.MenuRouteResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * The signed-in user's navigation: enabled menus granted by their roles, with the buttons they hold.
     */
    @GetMapping("/routes")
    public ApiResponse<List<MenuRouteResponse>> routes(Authentication authentication) {
        return ApiResponse.success(toResponses(menuService.routesFor(authentication.getName())));
    }

    /**
     * Every menu and button, for menu management and for assigning menus to roles.
     */
    @GetMapping("/tree")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Menus', 'Role')")
    public ApiResponse<List<MenuRouteResponse>> tree() {
        return ApiResponse.success(toResponses(menuService.tree()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Menus')")
    public ApiResponse<Long> create(@Valid @RequestBody MenuRequest request) {
        return ApiResponse.success(menuService.create(request.toCommand()).id());
    }

    @PutMapping("/{id}")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Menus')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody MenuRequest request) {
        menuService.update(id, request.toCommand());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Menus')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return ApiResponse.success(null);
    }

    private static List<MenuRouteResponse> toResponses(List<MenuNode> nodes) {
        return nodes.stream().map(MenuRouteResponse::from).toList();
    }
}
