package com.loggingx.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the {@code @Scheduled} jobs: the live log view's polling and the daily log retention purge.
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
}
