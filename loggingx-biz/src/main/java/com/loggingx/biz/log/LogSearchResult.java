package com.loggingx.biz.log;

import com.loggingx.common.api.PageResult;
import com.loggingx.dao.log.LogRecord;

import java.util.Map;

/**
 * @param levelCounts number of matching logs per level, ignoring the level filter, so the counts can drive it
 */
public record LogSearchResult(PageResult<LogRecord> page, Map<String, Long> levelCounts) {
}
