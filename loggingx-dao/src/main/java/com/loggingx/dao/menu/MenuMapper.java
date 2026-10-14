package com.loggingx.dao.menu;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MenuMapper {

    List<Menu> selectAll();

    Menu selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    void insert(@Param("menu") Menu menu, @Param("key") GeneratedKey key);

    void update(@Param("menu") Menu menu);

    void deleteById(@Param("id") Long id);
}
