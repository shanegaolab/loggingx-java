package com.loggingx.dao.ai;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AiMapper {

    AiSettings selectSettings();

    int updateSettings(@Param("value") AiSettings settings);

    void insertSettings(@Param("value") AiSettings settings);

    AiStoreConfig selectStoreConfig(@Param("storeId") Long storeId);

    List<AiStoreConfig> selectStoreConfigs();

    int updateStoreConfig(@Param("value") AiStoreConfig config);

    void insertStoreConfig(@Param("value") AiStoreConfig config);

    void updateScannedUntil(@Param("storeId") Long storeId, @Param("at") Instant at);

    void updateRepoState(@Param("storeId") Long storeId, @Param("commit") String commit,
                         @Param("syncedAt") Instant syncedAt, @Param("error") String error);

    ErrorGroup selectGroupByFingerprint(@Param("storeId") Long storeId, @Param("fingerprint") String fingerprint);

    ErrorGroup selectGroup(@Param("id") Long id);

    /**
     * @param storeId the store, or {@code null} for all
     * @param status  the status, or {@code null} for all
     */
    List<ErrorGroup> selectGroups(@Param("storeId") Long storeId, @Param("status") String status,
                                  @Param("limit") int limit);

    void insertGroup(@Param("group") ErrorGroup group, @Param("key") GeneratedKey key);

    void touchGroup(@Param("id") Long id, @Param("count") long count, @Param("lastSeen") Instant lastSeen,
                    @Param("sample") String sample, @Param("host") String host);

    void updateGroupStatus(@Param("id") Long id, @Param("status") String status);

    void updateGroupDiagnosis(@Param("id") Long id, @Param("diagnosisId") Long diagnosisId);

    void insertDiagnosis(@Param("value") AiDiagnosis diagnosis, @Param("key") GeneratedKey key);

    void updateDiagnosis(@Param("value") AiDiagnosis diagnosis);

    AiDiagnosis selectDiagnosis(@Param("id") Long id);

    /**
     * @param storeId     the store, or {@code null} for all
     * @param reviewState the review state, or {@code null} for all
     * @param triggerType the trigger, or {@code null} for all
     */
    List<AiDiagnosis> selectDiagnoses(@Param("storeId") Long storeId, @Param("reviewState") String reviewState,
                                      @Param("triggerType") String triggerType, @Param("since") Instant since,
                                      @Param("limit") int limit);

    /**
     * @return analyses started automatically (not manually) since then
     */
    int countAutomaticSince(@Param("since") Instant since);

    void updateReviewState(@Param("id") Long id, @Param("reviewState") String reviewState);

    /**
     * Analyses left pending or running when the application stopped are failed on start.
     */
    void failUnfinished(@Param("error") String error, @Param("at") Instant at);

    Instant selectNoticeSeen(@Param("username") String username);

    int updateNoticeSeen(@Param("username") String username, @Param("at") Instant at);

    void insertNoticeSeen(@Param("username") String username, @Param("at") Instant at);

    List<AiKnowledge> selectKnowledge();

    AiKnowledge selectKnowledgeById(@Param("id") Long id);

    void insertKnowledge(@Param("value") AiKnowledge knowledge, @Param("key") GeneratedKey key);

    void updateKnowledge(@Param("value") AiKnowledge knowledge);

    void deleteKnowledge(@Param("id") Long id);
}
