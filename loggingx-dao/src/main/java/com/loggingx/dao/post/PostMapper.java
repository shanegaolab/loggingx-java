package com.loggingx.dao.post;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PostMapper {

    List<Post> selectAll();

    Post selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    void insert(@Param("post") Post post, @Param("key") GeneratedKey key);

    void update(@Param("post") Post post);

    void deleteById(@Param("id") Long id);
}
