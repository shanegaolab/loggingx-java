package com.loggingx.common.api;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a list query, using the field names the frontend table expects.
 */
public record PageResult<T>(List<T> records, long current, long size, long total) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /**
     * Slices an already filtered and sorted list. Out-of-range page numbers fall back to sane values.
     */
    public static <T> PageResult<T> of(List<T> items, Integer current, Integer size) {
        int pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int pageNumber = current == null || current < 1 ? 1 : current;
        int from = (int) Math.min((long) (pageNumber - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return new PageResult<>(List.copyOf(items.subList(from, to)), pageNumber, pageSize, items.size());
    }

    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResult<>(records.stream().<R>map(mapper).toList(), current, size, total);
    }
}
