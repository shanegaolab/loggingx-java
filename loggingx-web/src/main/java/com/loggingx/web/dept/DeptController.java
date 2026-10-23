package com.loggingx.web.dept;

import com.loggingx.biz.dept.DeptQuery;
import com.loggingx.biz.dept.DeptService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.web.dept.dto.DeptRequest;
import com.loggingx.web.dept.dto.DeptResponse;
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
 * Department management. Every endpoint requires access to the department management menu.
 */
@RestController
@RequestMapping("/dept")
@PreAuthorize("@permissionService.hasAnyMenu(authentication, 'Dept')")
public class DeptController {

    private final DeptService deptService;

    public DeptController(DeptService deptService) {
        this.deptService = deptService;
    }

    @GetMapping("/tree")
    public ApiResponse<List<DeptResponse>> tree(DeptQuery query) {
        return ApiResponse.success(deptService.tree(query).stream().map(DeptResponse::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeptResponse> create(@Valid @RequestBody DeptRequest request) {
        return ApiResponse.success(DeptResponse.from(deptService.create(request.toCommand())));
    }

    @PutMapping("/{id}")
    public ApiResponse<DeptResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody DeptRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(DeptResponse.from(
                deptService.update(id, request.toCommand(), authentication.getName())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        deptService.delete(id, authentication.getName());
        return ApiResponse.success(null);
    }
}
