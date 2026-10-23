package com.loggingx.biz.dept;

import com.loggingx.biz.support.Filters;
import com.loggingx.biz.support.Updates;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.dept.DeptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Department administration. Departments form a tree; a department with child departments cannot be deleted, and
 * none can be moved below itself.
 */
@Service
public class DeptService {

    private static final Logger log = LoggerFactory.getLogger(DeptService.class);
    private static final String DEPT_NOT_FOUND = "Department was not found";
    private static final Comparator<Dept> ORDER = Comparator.comparingInt(Dept::sort).thenComparing(Dept::id);

    private final DeptRepository deptRepository;

    public DeptService(DeptRepository deptRepository) {
        this.deptRepository = deptRepository;
    }

    /**
     * @return the departments matching the query, with their ancestors so that the tree stays connected
     */
    public List<DeptNode> tree(DeptQuery query) {
        Predicate<Dept> matches = dept -> Filters.containsIgnoreCase(dept.name(), query.deptName())
                && Filters.equalsIfPresent(dept.enabled(), query.enabled());
        Map<Long, List<Dept>> byParent = deptRepository.findAll().stream()
                .sorted(ORDER)
                .collect(Collectors.groupingBy(Dept::parentId));
        return childrenOf(Dept.ROOT_PARENT_ID, byParent, matches);
    }

    public Dept create(DeptCommand command) {
        long parentId = parentIdOf(command);
        requireParent(parentId);
        Instant now = Instant.now();
        Dept dept = toDept(null, parentId, command, now, now);
        Dept created = deptRepository.insert(dept).orElseThrow(() -> duplicateName(dept.name()));
        log.info("event=dept.create.success deptId={} parentId={} deptName={}",
                created.id(), created.parentId(), created.name());
        return created;
    }

    public Dept update(Long id, DeptCommand command, String operatorUsername) {
        Dept existing = requireDept(id);
        long parentId = parentIdOf(command);
        if (parentId != existing.parentId()) {
            requireParent(parentId);
            if (isSelfOrDescendant(parentId, id)) {
                throw new BadRequestException("A department cannot be moved below itself");
            }
        }
        Dept updated = toDept(id, parentId, command, existing.createdAt(), Instant.now());
        Updates.require(deptRepository.update(existing, updated), DEPT_NOT_FOUND, () -> duplicateName(updated.name()));
        log.info("event=dept.update.success operator={} deptId={} parentId={} deptName={} enabled={}",
                operatorUsername, updated.id(), updated.parentId(), updated.name(), updated.enabled());
        return updated;
    }

    public void delete(Long id, String operatorUsername) {
        Dept dept = requireDept(id);
        if (deptRepository.findAll().stream().anyMatch(other -> other.parentId().equals(id))) {
            throw new BadRequestException("Delete the child departments first");
        }
        deptRepository.deleteById(id);
        log.info("event=dept.delete.success operator={} deptId={} deptName={}", operatorUsername, id, dept.name());
    }

    private List<DeptNode> childrenOf(Long parentId, Map<Long, List<Dept>> byParent, Predicate<Dept> matches) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .map(dept -> {
                    List<DeptNode> children = childrenOf(dept.id(), byParent, matches);
                    return matches.test(dept) || !children.isEmpty() ? new DeptNode(dept, children) : null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * @return whether {@code candidateId} is {@code deptId} or lies in its subtree
     */
    private boolean isSelfOrDescendant(Long candidateId, Long deptId) {
        Map<Long, Long> parents = deptRepository.findAll().stream()
                .collect(Collectors.toMap(Dept::id, Dept::parentId));
        // The depth bound guards against a cycle left behind by concurrent moves.
        Long current = candidateId;
        for (int depth = 0; depth <= parents.size() && current != null && current != Dept.ROOT_PARENT_ID; depth++) {
            if (current.equals(deptId)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    private void requireParent(long parentId) {
        if (parentId != Dept.ROOT_PARENT_ID && deptRepository.findById(parentId).isEmpty()) {
            throw new BadRequestException("Parent department was not found");
        }
    }

    private Dept requireDept(Long id) {
        return deptRepository.findById(id).orElseThrow(() -> new NotFoundException(DEPT_NOT_FOUND));
    }

    private static long parentIdOf(DeptCommand command) {
        return command.parentId() == null ? Dept.ROOT_PARENT_ID : command.parentId();
    }

    private static Dept toDept(Long id, long parentId, DeptCommand command, Instant createdAt, Instant updatedAt) {
        return new Dept(id, parentId, command.name().trim(), command.sort(), blankToNull(command.leader()),
                blankToNull(command.phone()), blankToNull(command.email()), command.enabled(), createdAt, updatedAt);
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException("Department '" + name + "' already exists under the same parent");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
