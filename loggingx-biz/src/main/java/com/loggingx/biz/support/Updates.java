package com.loggingx.biz.support;

import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.support.UpdateResult;

import java.util.function.Supplier;

/**
 * Turns a failed {@link UpdateResult} into the matching application exception.
 */
public final class Updates {

    public static final String STALE_MESSAGE = "The record was changed by someone else; reload it and try again";

    private Updates() {
    }

    public static void require(UpdateResult result, String notFoundMessage, Supplier<ConflictException> duplicateKey) {
        switch (result) {
            case UPDATED -> {
            }
            case NOT_FOUND -> throw new NotFoundException(notFoundMessage);
            case STALE -> throw new ConflictException(STALE_MESSAGE);
            case DUPLICATE_KEY -> throw duplicateKey.get();
        }
    }
}
