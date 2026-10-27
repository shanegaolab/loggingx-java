package com.loggingx.dao.monitor;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * Monitor configs, alert rules and alert events, kept in MySQL.
 */
@Mapper
public interface MonitorMapper {

    MonitorConfig selectConfig(@Param("storeId") Long storeId);

    int updateConfig(@Param("config") MonitorConfig config);

    void insertConfig(@Param("config") MonitorConfig config);

    List<AlertRule> selectRules();

    AlertRule selectRule(@Param("id") Long id);

    void insertRule(@Param("rule") AlertRule rule, @Param("key") GeneratedKey key);

    void updateRule(@Param("rule") AlertRule rule);

    void deleteRule(@Param("id") Long id);

    List<AlertEvent> selectFiringEvents();

    /**
     * @param status  {@code firing}, {@code resolved}, or {@code null} for both
     * @param storeId the store, or {@code null} for all
     * @param host    the host, or {@code null} for all
     */
    List<AlertEvent> selectEvents(@Param("status") String status, @Param("storeId") Long storeId,
                                  @Param("host") String host, @Param("since") Instant since,
                                  @Param("limit") int limit);

    void insertEvent(@Param("event") AlertEvent event, @Param("key") GeneratedKey key);

    void updateEvent(@Param("event") AlertEvent event);

    /**
     * Resolves the firing events of a deleted rule.
     */
    void resolveRuleEvents(@Param("ruleId") Long ruleId, @Param("at") Instant at);

    void deleteEventsBefore(@Param("before") Instant before);
}
