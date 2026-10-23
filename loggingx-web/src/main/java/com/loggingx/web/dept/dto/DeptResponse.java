package com.loggingx.web.dept.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loggingx.biz.dept.DeptNode;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.dept.Dept;

import java.util.List;

/**
 * A department, with its child departments when returned as part of the tree. {@code children} is omitted for
 * leaves so the frontend tree table shows no expand arrow for them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeptResponse(
        Long deptId,
        Long parentId,
        String deptName,
        int sort,
        String leader,
        String phone,
        String email,
        boolean enabled,
        String createTime,
        List<DeptResponse> children
) {

    public static DeptResponse from(Dept dept) {
        return from(dept, null);
    }

    public static DeptResponse from(DeptNode node) {
        List<DeptResponse> children = node.children().isEmpty()
                ? null
                : node.children().stream().map(DeptResponse::from).toList();
        return from(node.dept(), children);
    }

    private static DeptResponse from(Dept dept, List<DeptResponse> children) {
        return new DeptResponse(dept.id(), dept.parentId(), dept.name(), dept.sort(), dept.leader(), dept.phone(),
                dept.email(), dept.enabled(), DateTimes.format(dept.createdAt()), children);
    }
}
