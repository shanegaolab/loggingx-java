package com.loggingx.common.exception;

public final class BadRequestException extends ApplicationException {

    public BadRequestException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
