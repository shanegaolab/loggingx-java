package com.loggingx.dao.agent;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface LogAgentMapper {

    LogAgent selectById(@Param("id") Long id);

    LogAgent selectByKey(@Param("agentKey") String agentKey);

    /**
     * @param storeId the store, or {@code null} for all agents
     */
    List<LogAgent> selectByStore(@Param("storeId") Long storeId);

    List<AgentCount> countByStore(@Param("onlineSince") Instant onlineSince);

    void insert(@Param("agent") LogAgent agent, @Param("key") GeneratedKey key);

    void update(@Param("agent") LogAgent agent);

    void deleteById(@Param("id") Long id);
}
