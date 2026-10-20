package com.loggingx.ai.config;

import com.loggingx.ai.AiChatService;
import com.loggingx.ai.AiProvider;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registers a Spring AI chat model for each provider whose API key is configured, so the application starts with
 * any subset of keys. Spring AI's own chat auto-configurations would register every provider unconditionally
 * (and DeepSeek's fails without a key), so they are excluded in {@code spring.autoconfigure.exclude} and
 * imported here behind {@link ConditionalOnApiKey}. All other settings are Spring AI's standard
 * {@code spring.ai.openai.*}, {@code spring.ai.anthropic.*} and {@code spring.ai.deepseek.*} properties.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    @Bean
    public AiChatService aiChatService(
            ObjectProvider<OpenAiChatModel> openAi,
            ObjectProvider<AnthropicChatModel> anthropic,
            ObjectProvider<DeepSeekChatModel> deepSeek
    ) {
        Map<AiProvider, ChatModel> models = new EnumMap<>(AiProvider.class);
        openAi.ifAvailable(model -> models.put(AiProvider.OPENAI, model));
        anthropic.ifAvailable(model -> models.put(AiProvider.ANTHROPIC, model));
        deepSeek.ifAvailable(model -> models.put(AiProvider.DEEPSEEK, model));
        if (models.isEmpty()) {
            log.warn("No AI provider API key is configured; the AI chat API is unavailable");
        } else {
            log.info("AI providers configured: {}", models.keySet());
        }
        return new AiChatService(models);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnApiKey("spring.ai.openai")
    @Import(OpenAiChatAutoConfiguration.class)
    static class OpenAiConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnApiKey("spring.ai.anthropic")
    @Import(AnthropicChatAutoConfiguration.class)
    static class AnthropicConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnApiKey("spring.ai.deepseek")
    @Import(DeepSeekChatAutoConfiguration.class)
    static class DeepSeekConfiguration {
    }
}
