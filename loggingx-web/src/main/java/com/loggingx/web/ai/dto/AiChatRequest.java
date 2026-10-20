package com.loggingx.web.ai.dto;

import com.loggingx.ai.AiChatCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(
        @NotBlank(message = "provider must not be blank") String provider,
        @NotBlank(message = "message must not be blank")
        @Size(max = 20000, message = "message must be at most 20000 characters") String message,
        @Size(max = 20000, message = "systemPrompt must be at most 20000 characters") String systemPrompt,
        @Size(max = 100, message = "model must be at most 100 characters") String model
) {

    public AiChatCommand toCommand() {
        return new AiChatCommand(provider, message, systemPrompt, model);
    }
}
