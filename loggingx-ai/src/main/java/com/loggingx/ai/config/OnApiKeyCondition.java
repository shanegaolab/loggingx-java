package com.loggingx.ai.config;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

final class OnApiKeyCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnApiKey.class.getName());
        String name = attributes.get("value") + ".api-key";
        return StringUtils.hasText(context.getEnvironment().getProperty(name))
                ? ConditionOutcome.match(name + " is set")
                : ConditionOutcome.noMatch(name + " is not set");
    }
}
