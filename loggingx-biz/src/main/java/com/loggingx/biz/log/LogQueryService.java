package com.loggingx.biz.log;

import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.dao.log.LevelCount;
import com.loggingx.dao.log.LogLevel;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Searches a store's logs, newest first. Deep pages are refused: the offset query gets slower the further it goes,
 * so callers should narrow the time range instead.
 */
@Service
public class LogQueryService {

    /**
     * How many matching logs can be paged through.
     */
    public static final int MAX_RESULT_WINDOW = 10_000;

    private final LogStoreService logStoreService;
    private final LogRecordRepository logRecordRepository;

    public LogQueryService(LogStoreService logStoreService, LogRecordRepository logRecordRepository) {
        this.logStoreService = logStoreService;
        this.logRecordRepository = logRecordRepository;
    }

    public LogSearchResult search(LogQuery query) {
        if (query.storeId() == null) {
            throw new BadRequestException("storeId is required");
        }
        logStoreService.require(query.storeId());
        if (query.start() != null && query.end() != null && !query.start().isBefore(query.end())) {
            throw new BadRequestException("The start time must be before the end time");
        }
        int size = query.size() == null || query.size() < 1 ? PageResult.DEFAULT_SIZE
                : Math.min(query.size(), PageResult.MAX_SIZE);
        int current = query.current() == null || query.current() < 1 ? 1 : query.current();
        long offset = (long) (current - 1) * size;
        if (offset + size > MAX_RESULT_WINDOW) {
            throw new BadRequestException("Only the first " + MAX_RESULT_WINDOW
                    + " matching logs can be paged through; narrow the time range or filters");
        }

        List<String> levels = query.levels() == null ? List.of() : query.levels().stream()
                .filter(level -> level != null && !level.isBlank())
                .map(level -> level.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        LogSearch search = new LogSearch(query.storeId(), query.start(), query.end(), levels,
                blankToNull(query.keyword()), blankToNull(query.host()), blankToNull(query.traceId()));
        List<LogRecord> records = logRecordRepository.search(search, offset, size);
        long total = logRecordRepository.count(search);

        LogSearch anyLevel = new LogSearch(search.storeId(), search.start(), search.end(), List.of(),
                search.keyword(), search.host(), search.traceId());
        Map<String, Long> levelCounts = new LinkedHashMap<>();
        Arrays.stream(LogLevel.values()).forEach(level -> levelCounts.put(level.name(), 0L));
        for (LevelCount count : logRecordRepository.countByLevel(anyLevel)) {
            levelCounts.merge(count.level(), count.count(), Long::sum);
        }
        return new LogSearchResult(new PageResult<>(records, current, size, total), levelCounts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
