package com.loggingx;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally on the test setup: an in-memory H2 database, an in-process Redis server and in-memory
 * log storage (see src/test/resources/config), so no MySQL, Redis or ClickHouse is needed. Data is lost on exit.
 * <pre>
 * mvn -pl loggingx-web -am install -DskipTests
 * mvn -pl loggingx-web spring-boot:test-run -Dspring-boot.run.profiles=dev
 * </pre>
 */
public class TestLoggingxApplication {

    public static void main(String[] args) {
        SpringApplication.from(LoggingxApplication::main).run(args);
    }
}
