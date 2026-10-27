package com.loggingx.biz.log;

/**
 * @param accepted  entries stored
 * @param rejected  entries dropped because their message was blank
 * @param duplicate entries not stored again because the same batch was stored already (a retried request)
 */
public record IngestResult(int accepted, int rejected, int duplicate) {

    public IngestResult(int accepted, int rejected) {
        this(accepted, rejected, 0);
    }
}
