package com.loggingx.dao.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface CollectConfigMapper {

    /**
     * A stored config; paths are kept one per line.
     */
    record Row(Long storeId, String paths, String windowsPaths, boolean containers, String multiline,
               String multilinePattern, String transport, String kafkaServers, String kafkaTopic,
               Instant updatedAt) {
    }

    Row selectByStore(@Param("storeId") Long storeId);

    int update(@Param("row") Row row);

    void insert(@Param("row") Row row);
}
