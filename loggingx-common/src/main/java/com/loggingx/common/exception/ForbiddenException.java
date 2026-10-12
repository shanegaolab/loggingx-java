package com.loggingx.common.exception;

public final class ForbiddenException extends ApplicationException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
