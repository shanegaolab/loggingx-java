package com.loggingx.dao.account;

import com.loggingx.dao.support.GeneratedKey;
import com.loggingx.dao.support.Link;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * Accounts are mapped without roles, department or posts; those come from {@link #selectRoleLinks},
 * {@link #selectDeptLinks} and {@link #selectPostLinks}.
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

    /**
     * @param userIds the users whose department to read, or {@code null} for every user
     */
    List<Link> selectDeptLinks(@Param("userIds") Collection<Long> userIds);

    void deleteDeptLinks(@Param("userId") Long userId, @Param("deptIds") Collection<Long> deptIds);

    void insertDeptLinks(@Param("userId") Long userId, @Param("deptIds") Collection<Long> deptIds);

    /**
     * @param userIds the users whose posts to read, or {@code null} for every user
     */
    List<Link> selectPostLinks(@Param("userIds") Collection<Long> userIds);

    void deletePostLinks(@Param("userId") Long userId, @Param("postIds") Collection<Long> postIds);

    void insertPostLinks(@Param("userId") Long userId, @Param("postIds") Collection<Long> postIds);

    boolean existsInDept(@Param("deptId") Long deptId);

    boolean existsWithPost(@Param("postId") Long postId);
}
