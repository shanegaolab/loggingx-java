package com.loggingx.web.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The log collection agent (Vector) that the agent page and install scripts deploy ({@code loggingx.agent.*}).
 *
 * @param version     Vector release to install, e.g. {@code 0.58.0}
 * @param upstreamUrl where release packages are downloaded from when they are not mirrored locally; the package
 *                    file name is appended as {@code <upstreamUrl>/v<version>/<file>}
 * @param packageDir  local directory with mirrored release packages (and the {@code SHA256SUMS} file), served instead
 *                    of the upstream for hosts without internet access; blank to always use the upstream
 * @param image       container image for Docker and Kubernetes, e.g. {@code timberio/vector:0.58.0-alpine}
 * @param serverUrl   base URL agents use to reach LoggingX, e.g. {@code https://logs.example.com}; blank to use the
 *                    URL the page is opened with
 */
@ConfigurationProperties("loggingx.agent")
public record AgentProperties(
        String version,
        String upstreamUrl,
        String packageDir,
        String image,
        String serverUrl
) {

    public AgentProperties {
        version = blankOr(version, "0.58.0");
        upstreamUrl = stripTrailingSlash(blankOr(upstreamUrl, "https://github.com/vectordotdev/vector/releases/download"));
        packageDir = blankOr(packageDir, "");
        image = blankOr(image, "timberio/vector:" + version + "-alpine");
        serverUrl = stripTrailingSlash(blankOr(serverUrl, ""));
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
