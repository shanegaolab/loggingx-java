package com.loggingx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Runs on the embedded Tomcat through {@link #main}, or on an external Tomcat as a WAR through
 * {@link SpringBootServletInitializer}.
 */
@SpringBootApplication
public class LoggingxApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(LoggingxApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(LoggingxApplication.class);
    }
}
