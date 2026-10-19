package com.loggingx.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    /**
     * Prefix applied to every REST controller in the web module, e.g. {@code /api/user/list}.
     */
    private final String apiPrefix;

    public WebMvcConfiguration(@Value("${api.prefix:/api}") String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(apiPrefix, HandlerTypePredicate.forBasePackage("com.loggingx.web")
                .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OperationAuditInterceptor());
    }
}
