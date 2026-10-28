package com.loggingx.dao.role;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoleStoreScopeMapper {

    RoleStoreScope selectScope(@Param("roleId") Long roleId);

    List<RoleStoreScope> selectScopes();

    int updateScope(@Param("value") RoleStoreScope scope);

    void insertScope(@Param("value") RoleStoreScope scope);
}
