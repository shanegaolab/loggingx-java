package com.loggingx.ai;

/**
 * The model's answer. Token counts are {@code null} when the provider does not report them.
 */
public record AiChatReply(
        String provider,
        String model,
        String content,
        Integer promptTokens,
        Integer completionTokens
) {
}
