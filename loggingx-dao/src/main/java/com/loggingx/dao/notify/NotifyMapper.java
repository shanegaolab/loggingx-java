package com.loggingx.dao.notify;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface NotifyMapper {

    NotifyChannel selectChannel(@Param("channel") String channel);

    int updateChannel(@Param("value") NotifyChannel channel);

    void insertChannel(@Param("value") NotifyChannel channel);

    List<NotifyGroup> selectGroups();

    NotifyGroup selectGroup(@Param("id") Long id);

    void insertGroup(@Param("group") NotifyGroup group, @Param("key") GeneratedKey key);

    void updateGroup(@Param("group") NotifyGroup group);

    void deleteGroup(@Param("id") Long id);

    AlertNotify selectRuleNotify(@Param("ruleType") String ruleType, @Param("ruleId") Long ruleId);

    List<AlertNotify> selectRuleNotifies(@Param("ruleType") String ruleType);

    int updateRuleNotify(@Param("value") AlertNotify notify);

    void insertRuleNotify(@Param("value") AlertNotify notify);

    void deleteRuleNotify(@Param("ruleType") String ruleType, @Param("ruleId") Long ruleId);

    void insertRecord(@Param("record") NotifyRecord record);

    /**
     * @param eventType {@code host}, {@code log}, {@code test}, or {@code null} for all
     * @param eventId   the event, or {@code null} for all
     */
    List<NotifyRecord> selectRecords(@Param("eventType") String eventType, @Param("eventId") Long eventId,
                                     @Param("limit") int limit);

    /**
     * @return when the event was last notified (any channel, any outcome), or {@code null}
     */
    Instant selectLastSent(@Param("eventType") String eventType, @Param("eventId") Long eventId);

    void deleteRecordsBefore(@Param("before") Instant before);
}
