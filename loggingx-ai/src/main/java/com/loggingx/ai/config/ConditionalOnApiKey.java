package com.loggingx.ai.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when {@code <prefix>.api-key} is set to a non-blank value.
 * Unlike {@code @ConditionalOnProperty}, an empty value (the default of {@code ${ENV_VAR:}}) does not match.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnApiKeyCondition.class)
@interface ConditionalOnApiKey {

    /**
     * The provider's property prefix, e.g. {@code spring.ai.openai}.
     */
    String value();
}
