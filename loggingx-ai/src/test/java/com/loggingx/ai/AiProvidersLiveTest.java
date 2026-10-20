package com.loggingx.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.loggingx.ai.config.AiConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Calls the real provider APIs; skipped unless {@code LOGGINGX_AI_LIVE=true}. Uses the same environment variables
 * as the application ({@code LOGGINGX_AI_<PROVIDER>_API_KEY}, {@code _BASE_URL}, {@code _MODEL}) and checks every
 * provider whose key is set, with a plain and a streamed request:
 *
 * <pre>
 * LOGGINGX_AI_LIVE=true LOGGINGX_AI_DEEPSEEK_API_KEY=sk-... mvn test -pl loggingx-ai -Dtest=AiProvidersLiveTest
 * </pre>
 *
 * OpenAI and Anthropic honour the JVM proxy settings, e.g. {@code -DargLine="-Dhttps.proxyHost=127.0.0.1
 * -Dhttps.proxyPort=7897"}.
 */
@EnabledIfEnvironmentVariable(named = "LOGGINGX_AI_LIVE", matches = "true")
class AiProvidersLiveTest {

    @Test
    void everyConfiguredProviderAnswers() {
        List<String> properties = new ArrayList<>();
        for (AiProvider provider : AiProvider.values()) {
            String env = "LOGGINGX_AI_" + provider.name() + "_";
            String prefix = "spring.ai." + provider.id();
            addIfSet(properties, prefix + ".api-key", env + "API_KEY");
            addIfSet(properties, prefix + ".base-url", env + "BASE_URL");
            addIfSet(properties, prefix + ".chat.options.model", env + "MODEL");
        }

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class))
                .withUserConfiguration(AiConfiguration.class)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    AiChatService service = context.getBean(AiChatService.class);
                    assertThat(service.providers()).as("providers with an API key").isNotEmpty();
                    for (AiProvider provider : service.providers()) {
                        AiChatReply reply = service.chat(new AiChatCommand(
                                provider.id(), "Reply with the single word: pong", "You are a connectivity check.",
                                null));
                        System.out.println("[live] " + reply);
                        assertThat(reply.content()).as(provider.id()).isNotBlank();
                        assertThat(reply.model()).as(provider.id()).isNotBlank();

                        List<AiChatStreamEvent> events = service.stream(new AiChatCommand(
                                        provider.id(), "Count from 1 to 5, separated by spaces.", null, null))
                                .collectList()
                                .block(Duration.ofMinutes(2));
                        System.out.println("[live] " + provider.id() + " stream: " + events);
                        assertThat(events).as(provider.id() + " stream").last()
                                .isInstanceOfSatisfying(AiChatStreamEvent.Done.class,
                                        done -> assertThat(done.reply().content()).isNotBlank());
                    }
                });
    }

    private static void addIfSet(List<String> properties, String property, String env) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            properties.add(property + "=" + value);
        }
    }
}
