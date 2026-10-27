package com.loggingx.dao.log;

import com.loggingx.dao.support.EntityRepository;

import java.util.Optional;

/**
 * Codes and ingest tokens are unique; {@link #insert} and {@link #update} refuse one that is already taken.
 */
public interface LogStoreRepository extends EntityRepository<LogStore> {

    Optional<LogStore> findByCode(String code);

    Optional<LogStore> findByIngestToken(String ingestToken);
}
