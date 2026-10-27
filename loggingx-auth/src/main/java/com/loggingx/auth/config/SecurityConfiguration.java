package com.loggingx.auth.config;

import com.loggingx.auth.security.ApiErrorResponder;
import com.loggingx.auth.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        ApiErrorResponder errorResponder = new ApiErrorResponder();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // A streamed response (e.g. /api/ai/chat/stream) is written in an async dispatch of a request
                        // already authorized above; JwtAuthenticationFilter does not run again for it.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        // Log producers authenticate with a log store's ingest token, checked by the controller.
                        .requestMatchers(HttpMethod.POST, "/api/logs/ingest").permitAll()
                        // Agents report host metrics with the same token.
                        .requestMatchers(HttpMethod.POST, "/api/metrics/ingest").permitAll()
                        // Agent hosts fetch configs and install scripts with the ingest token, which the controller
                        // checks (signed-in managers are accepted too); agent packages are public.
                        .requestMatchers(HttpMethod.GET, "/api/agent/config", "/api/agent/install.sh",
                                "/api/agent/install.ps1", "/api/agent/kubernetes.yaml", "/api/agent/download/**")
                        .permitAll()
                        // Archive downloads are authorized by a short-lived signed token in the path.
                        .requestMatchers(HttpMethod.GET, "/api/backup/download/**").permitAll()
                        // Installed agents sync with their own id and secret, checked by the controller.
                        .requestMatchers(HttpMethod.GET, "/api/agent/sync").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/sync").permitAll()
                        .anyRequest().authenticated())
                // Without basic/form login there is no entry point, and Spring would answer 403 instead of 401.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
