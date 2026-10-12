package com.loggingx.web.user;

import com.loggingx.biz.user.UserQuery;
import com.loggingx.biz.user.UserService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.api.PageResult;
import com.loggingx.web.user.dto.UserInfoResponse;
import com.loggingx.web.user.dto.UserListItemResponse;
import com.loggingx.web.user.dto.UserRequest;
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

@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/info")
    public ApiResponse<UserInfoResponse> currentUser(Authentication authentication) {
        return ApiResponse.success(UserInfoResponse.from(userService.info(authentication.getName())));
    }

    @GetMapping("/list")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'User')")
    public ApiResponse<PageResult<UserListItemResponse>> list(UserQuery query) {
        return ApiResponse.success(userService.page(query).map(UserListItemResponse::from));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'User')")
    public ApiResponse<UserListItemResponse> create(
            @Valid @RequestBody UserRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(UserListItemResponse.from(
                userService.create(request.toCommand(), authentication.getName())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'User')")
    public ApiResponse<UserListItemResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(UserListItemResponse.from(
                userService.update(id, request.toCommand(), authentication.getName())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'User')")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        userService.delete(id, authentication.getName());
        return ApiResponse.success(null);
    }
}
