package com.loggingx.common.exception;

public final class TooManyRequestsException extends ApplicationException {

    public TooManyRequestsException(String message) {
        super(ErrorCode.TOO_MANY_REQUESTS, message);
    }
}
