package com.loggingx.dao.monitor;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface LogAlertMapper {

    List<LogAlertRule> selectRules();

    LogAlertRule selectRule(@Param("id") Long id);

    void insertRule(@Param("rule") LogAlertRule rule, @Param("key") GeneratedKey key);

    void updateRule(@Param("rule") LogAlertRule rule);

    void deleteRule(@Param("id") Long id);

    List<LogAlertEvent> selectFiringEvents();

    /**
     * @param status  {@code firing}, {@code resolved}, or {@code null} for both
     * @param storeId the store, or {@code null} for all
     */
    List<LogAlertEvent> selectEvents(@Param("status") String status, @Param("storeId") Long storeId,
                                     @Param("since") Instant since, @Param("limit") int limit);

    void insertEvent(@Param("event") LogAlertEvent event, @Param("key") GeneratedKey key);

    void updateEvent(@Param("event") LogAlertEvent event);

    void resolveRuleEvents(@Param("ruleId") Long ruleId, @Param("at") Instant at);

    void deleteEventsBefore(@Param("before") Instant before);
}
