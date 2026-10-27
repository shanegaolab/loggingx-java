package com.loggingx.web.log.dto;

import com.loggingx.biz.log.LogSearchResult;

import java.util.List;
import java.util.Map;

/**
 * One page of matching logs, newest first, in the frontend's paging shape.
 *
 * @param levelCounts matching logs per level, ignoring the level filter
 */
public record LogSearchResponse(
        List<LogRecordResponse> records,
        long current,
        long size,
        long total,
        Map<String, Long> levelCounts
) {

    public static LogSearchResponse from(LogSearchResult result) {
        return new LogSearchResponse(result.page().records().stream().map(LogRecordResponse::from).toList(),
                result.page().current(), result.page().size(), result.page().total(), result.levelCounts());
    }
}
