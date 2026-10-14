package com.loggingx.dao.account;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;
import java.util.Set;

/**
 * @param tokenVersion incremented to revoke every token issued before, e.g. after a password change
 * @param gender       1 male, 2 female
 * @param status       1 online, 2 offline, 3 abnormal, 4 deactivated
 * @param roleIds      ids of the roles granted to the user
 */
public record UserAccount(
        Long id,
        String username,
        String encodedPassword,
        int tokenVersion,
        String nickName,
        String gender,
        String phone,
        String email,
        String avatar,
        String status,
        Set<Long> roleIds,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {

    public static final String STATUS_ONLINE = "1";
    public static final String STATUS_DEACTIVATED = "4";

    public UserAccount {
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    /**
     * Creates an account without roles; the persistence layer maps rows with it and adds the roles separately.
     */
    public UserAccount(Long id, String username, String encodedPassword, int tokenVersion, String nickName,
                       String gender, String phone, String email, String avatar, String status, Instant createdAt,
                       String createdBy, Instant updatedAt, String updatedBy) {
        this(id, username, encodedPassword, tokenVersion, nickName, gender, phone, email, avatar, status, Set.of(),
                createdAt, createdBy, updatedAt, updatedBy);
    }

    public UserAccount withId(Long newId) {
        return new UserAccount(newId, username, encodedPassword, tokenVersion, nickName, gender, phone, email, avatar,
                status, roleIds, createdAt, createdBy, updatedAt, updatedBy);
    }

    public UserAccount withRoleIds(Set<Long> newRoleIds) {
        return new UserAccount(id, username, encodedPassword, tokenVersion, nickName, gender, phone, email, avatar,
                status, newRoleIds, createdAt, createdBy, updatedAt, updatedBy);
    }

    public boolean isDeactivated() {
        return STATUS_DEACTIVATED.equals(status);
    }
}
