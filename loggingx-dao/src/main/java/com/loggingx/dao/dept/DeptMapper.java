package com.loggingx.dao.dept;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DeptMapper {

    List<Dept> selectAll();

    Dept selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    void insert(@Param("dept") Dept dept, @Param("key") GeneratedKey key);

    void update(@Param("dept") Dept dept);

    void deleteById(@Param("id") Long id);
}
