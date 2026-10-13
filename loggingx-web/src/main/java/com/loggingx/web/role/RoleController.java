package com.loggingx.web.role;

import com.loggingx.biz.role.RoleQuery;
import com.loggingx.biz.role.RoleService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.api.PageResult;
import com.loggingx.web.role.dto.RoleListItemResponse;
import com.loggingx.web.role.dto.RoleMenusRequest;
import com.loggingx.web.role.dto.RoleRequest;
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

/**
 * Role management. Every endpoint requires access to the role management menu unless it declares its own rule.
 */
@RestController
@RequestMapping("/role")
@PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Role')")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/list")
    public ApiResponse<PageResult<RoleListItemResponse>> list(RoleQuery query) {
        return ApiResponse.success(roleService.page(query).map(RoleListItemResponse::from));
    }

    /**
     * Enabled roles for selection lists, such as the role picker in the user dialog, so user managers can use it too.
     */
    @GetMapping("/options")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'User', 'Role')")
    public ApiResponse<List<RoleListItemResponse>> options() {
        return ApiResponse.success(roleService.enabledRoles().stream().map(RoleListItemResponse::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoleListItemResponse> create(@Valid @RequestBody RoleRequest request) {
        return ApiResponse.success(RoleListItemResponse.from(roleService.create(request.toCommand())));
    }

    @PutMapping("/{id}")
    public ApiResponse<RoleListItemResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody RoleRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(RoleListItemResponse.from(
                roleService.update(id, request.toCommand(), authentication.getName())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        roleService.delete(id, authentication.getName());
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/menus")
    public ApiResponse<List<Long>> menus(@PathVariable Long id) {
        return ApiResponse.success(roleService.menuIdsOf(id));
    }

    @PutMapping("/{id}/menus")
    public ApiResponse<Void> assignMenus(
            @PathVariable Long id,
            @Valid @RequestBody RoleMenusRequest request,
            Authentication authentication
    ) {
        roleService.assignMenus(id, request.menuIds(), authentication.getName());
        return ApiResponse.success(null);
    }
}
