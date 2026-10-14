package com.loggingx.dao.support;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toSet;

/**
 * A row of a many-to-many join table, such as a role granted to a user.
 */
public record Link(Long ownerId, Long memberId) {

    /**
     * @return member ids by owner id; owners without links are absent
     */
    public static Map<Long, Set<Long>> group(Collection<Link> links) {
        return links.stream().collect(groupingBy(Link::ownerId, mapping(Link::memberId, toSet())));
    }
}
