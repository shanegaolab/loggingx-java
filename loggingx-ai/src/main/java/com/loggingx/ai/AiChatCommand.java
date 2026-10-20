package com.loggingx.ai;

/**
 * One chat turn. {@code systemPrompt} and {@code model} are optional; without a model the provider's
 * configured default ({@code spring.ai.<provider>.chat.options.model}) is used.
 */
public record AiChatCommand(String provider, String message, String systemPrompt, String model) {
}
