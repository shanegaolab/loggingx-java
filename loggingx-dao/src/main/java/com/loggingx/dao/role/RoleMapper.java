package com.loggingx.dao.role;

import com.loggingx.dao.support.GeneratedKey;
import com.loggingx.dao.support.Link;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * Roles are mapped without menus; the menus come from {@link #selectMenuLinks}.
 */
@Mapper
public interface RoleMapper {

    List<Role> selectAll();

    Role selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    Role selectByCode(@Param("code") String code);

    void insert(@Param("role") Role role, @Param("key") GeneratedKey key);

    void update(@Param("role") Role role);

    void deleteById(@Param("id") Long id);

    /**
     * @param roleIds the roles whose menus to read, or {@code null} for every role
     */
    List<Link> selectMenuLinks(@Param("roleIds") Collection<Long> roleIds);

    void deleteMenuLinks(@Param("roleId") Long roleId, @Param("menuIds") Collection<Long> menuIds);

    void insertMenuLinks(@Param("roleId") Long roleId, @Param("menuIds") Collection<Long> menuIds);
}
