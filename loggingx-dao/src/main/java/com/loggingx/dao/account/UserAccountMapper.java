package com.loggingx.dao.account;

import com.loggingx.dao.support.GeneratedKey;
import com.loggingx.dao.support.Link;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * Accounts are mapped without roles; the roles come from {@link #selectRoleLinks}.
 */
@Mapper
public interface UserAccountMapper {

    List<UserAccount> selectAll();

    UserAccount selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    UserAccount selectByUsername(@Param("username") String username);

    void insert(@Param("account") UserAccount account, @Param("key") GeneratedKey key);

    void update(@Param("account") UserAccount account);

    void deleteById(@Param("id") Long id);

    /**
     * @param userIds the users whose roles to read, or {@code null} for every user
     */
    List<Link> selectRoleLinks(@Param("userIds") Collection<Long> userIds);

    void deleteRoleLinks(@Param("userId") Long userId, @Param("roleIds") Collection<Long> roleIds);

    void insertRoleLinks(@Param("userId") Long userId, @Param("roleIds") Collection<Long> roleIds);
}
