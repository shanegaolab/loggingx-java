package com.loggingx.biz.dept;

import com.loggingx.dao.dept.Dept;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Walks the department tree. Both directions stop at departments already visited, guarding against a cycle left
 * behind by concurrent department moves.
 */
public final class DeptHierarchy {

    private DeptHierarchy() {
    }

    /**
     * @return {@code deptId} and the ids of all departments below it
     */
    public static Set<Long> selfAndDescendants(Long deptId, List<Dept> depts) {
        Map<Long, List<Long>> childrenByParent = depts.stream()
                .collect(Collectors.groupingBy(Dept::parentId, Collectors.mapping(Dept::id, Collectors.toList())));
        Set<Long> result = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(List.of(deptId));
        while (!pending.isEmpty()) {
            Long current = pending.pop();
            if (result.add(current)) {
                pending.addAll(childrenByParent.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    /**
     * @return {@code deptId} and the ids of all departments above it; empty when {@code deptId} is {@code null}
     */
    public static Set<Long> selfAndAncestors(Long deptId, List<Dept> depts) {
        Map<Long, Dept> byId = depts.stream().collect(Collectors.toMap(Dept::id, Function.identity()));
        Set<Long> result = new HashSet<>();
        Long current = deptId;
        while (current != null && current != Dept.ROOT_PARENT_ID && result.add(current)) {
            Dept dept = byId.get(current);
            current = dept == null ? null : dept.parentId();
        }
        return result;
    }
}
