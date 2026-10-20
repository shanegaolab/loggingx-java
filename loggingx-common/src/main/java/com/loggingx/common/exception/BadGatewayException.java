package com.loggingx.common.exception;

/**
 * An external service the request depends on failed or could not be reached.
 */
public final class BadGatewayException extends ApplicationException {

    public BadGatewayException(String message) {
        super(ErrorCode.BAD_GATEWAY, message);
    }
}
