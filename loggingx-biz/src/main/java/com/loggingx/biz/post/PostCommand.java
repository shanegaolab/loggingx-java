package com.loggingx.biz.post;

public record PostCommand(String code, String name, int sort, boolean enabled, String remark) {
}
