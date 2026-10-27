package com.loggingx.dao.post;

import com.loggingx.dao.support.GeneratedKey;
import com.loggingx.dao.support.Link;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * Posts are mapped without departments; those come from {@link #selectDeptLinks}.
 */
@Mapper
public interface PostMapper {

    List<Post> selectAll();

    Post selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    void insert(@Param("post") Post post, @Param("key") GeneratedKey key);

    void update(@Param("post") Post post);

    void deleteById(@Param("id") Long id);

    /**
     * @param postIds the posts whose departments to read, or {@code null} for every post
     */
    List<Link> selectDeptLinks(@Param("postIds") Collection<Long> postIds);

    void deleteDeptLinks(@Param("postId") Long postId, @Param("deptIds") Collection<Long> deptIds);

    void insertDeptLinks(@Param("postId") Long postId, @Param("deptIds") Collection<Long> deptIds);

    boolean existsForDept(@Param("deptId") Long deptId);
}
