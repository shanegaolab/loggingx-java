package com.loggingx.dao.backup;

import com.loggingx.dao.support.GeneratedKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BackupMapper {

    BackupStorage selectStorage();

    int updateStorage(@Param("storage") BackupStorage storage);

    void insertStorage(@Param("storage") BackupStorage storage);

    List<BackupPolicy> selectPolicies();

    BackupPolicy selectPolicy(@Param("storeId") Long storeId);

    int updatePolicy(@Param("policy") BackupPolicy policy);

    void insertPolicy(@Param("policy") BackupPolicy policy);

    /**
     * @param storeId the store, or {@code null} for all
     * @param status  {@code success}, {@code failed}, or {@code null} for both
     */
    List<BackupArchive> selectArchives(@Param("storeId") Long storeId, @Param("status") String status,
                                       @Param("limit") int limit);

    BackupArchive selectArchive(@Param("id") Long id);

    BackupArchive selectArchiveByDay(@Param("storeId") Long storeId, @Param("day") LocalDate day);

    /**
     * @return the successful archives of the store's days before {@code before}
     */
    List<BackupArchive> selectArchivesBefore(@Param("storeId") Long storeId, @Param("before") LocalDate before);

    /**
     * @return the archives whose restored logs are kept beyond {@code at}
     */
    List<BackupArchive> selectRestoredAfter(@Param("at") Instant at);

    void insertArchive(@Param("archive") BackupArchive archive, @Param("key") GeneratedKey key);

    void updateArchive(@Param("archive") BackupArchive archive);

    void deleteArchive(@Param("id") Long id);
}
