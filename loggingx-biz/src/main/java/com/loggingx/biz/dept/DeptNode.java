package com.loggingx.biz.dept;

import com.loggingx.dao.dept.Dept;

import java.util.List;

/**
 * A department with its child departments, ordered by sort value.
 */
public record DeptNode(Dept dept, List<DeptNode> children) {
}
