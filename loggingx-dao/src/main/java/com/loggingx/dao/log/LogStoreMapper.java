package com.loggingx.dao.log;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LogStoreMapper {

    List<LogStore> selectAll();

    LogStore selectById(@Param("id") Long id, @Param("forUpdate") boolean forUpdate);

    LogStore selectByCode(@Param("code") String code);

    LogStore selectByIngestToken(@Param("ingestToken") String ingestToken);

    void insert(@Param("store") LogStore store, @Param("key") GeneratedKey key);

    void update(@Param("store") LogStore store);

    void deleteById(@Param("id") Long id);
}
