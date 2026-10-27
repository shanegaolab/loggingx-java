package com.loggingx.web.agent;

import com.loggingx.common.exception.BadRequestException;
import com.loggingx.dao.agent.CollectConfig;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How an agent is deployed and what it collects, as chosen on the agent page. Every value ends up in a generated
 * config or script, so all of them are validated here.
 *
 * @param paths            log files to collect (globs allowed); for Docker they are host paths, mounted read-only;
 *                         ignored for Kubernetes, which collects pod logs
 * @param containers       Docker only: also collect the stdout/stderr of all containers
 * @param multilinePattern {@link Multiline#CUSTOM} only: regex that matches the first line of each log entry
 * @param serverUrl        base URL of LoggingX as the agent reaches it
 * @param kafkaServers     {@link Transport#KAFKA} only: bootstrap servers, e.g. {@code kafka-1:9092,kafka-2:9092}
 * @param kafkaTopic       {@link Transport#KAFKA} only
 */
public record AgentOptions(
        Platform platform,
        Transport transport,
        List<String> paths,
        boolean containers,
        Multiline multiline,
        String multilinePattern,
        String serverUrl,
        String kafkaServers,
        String kafkaTopic
) {

    private static final int MAX_PATHS = 20;
    private static final Pattern SAFE_PATH = Pattern.compile("[\\w\\-./:\\\\*?\\[\\]{}, ]+");
    private static final Pattern KAFKA_SERVERS = Pattern.compile("[\\w.\\-]+:\\d{1,5}(,[\\w.\\-]+:\\d{1,5})*");
    private static final Pattern KAFKA_TOPIC = Pattern.compile("[\\w.\\-]{1,249}");

    public enum Platform {
        LINUX, DOCKER, KUBERNETES, WINDOWS
    }

    public enum Transport {
        HTTP, KAFKA
    }

    /**
     * How lines are merged into multi-line entries such as stack traces.
     */
    public enum Multiline {
        /** Every line is an entry. */
        NONE,
        /**
         * Indented lines, {@code Caused by:} lines and exception lines ({@code ...Exception: message}) continue the
         * previous entry (Java, Python, .NET traces).
         */
        STACKTRACE,
        /** Each entry starts with a date such as {@code 2026-10-23}; other lines continue it. */
        TIMESTAMP,
        /** Each entry starts with a line matching {@code multilinePattern}. */
        CUSTOM
    }

    /**
     * Builds the options from request parameters, applying platform defaults.
     *
     * @throws BadRequestException when a value is missing or unsafe to put in a config or script
     */
    public static AgentOptions of(String platform, String transport, List<String> paths, Boolean containers,
                                  String multiline, String multilinePattern, String serverUrl, String kafkaServers,
                                  String kafkaTopic) {
        Platform parsedPlatform = parse(Platform.class, platform, Platform.LINUX, "platform");
        Transport parsedTransport = parse(Transport.class, transport, Transport.HTTP, "transport");
        Multiline parsedMultiline = parse(Multiline.class, multiline, Multiline.NONE, "multiline");

        // Kubernetes collects the pods' logs; node files are not mounted into the agent.
        List<String> cleanPaths = paths == null || parsedPlatform == Platform.KUBERNETES ? List.of() : paths.stream()
                .flatMap(value -> List.of(value.split("[\\n;]")).stream())
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (cleanPaths.size() > MAX_PATHS) {
            throw new BadRequestException("At most " + MAX_PATHS + " paths can be collected");
        }
        for (String path : cleanPaths) {
            if (!SAFE_PATH.matcher(path).matches()) {
                throw new BadRequestException("Path '" + path + "' contains unsupported characters");
            }
            boolean absolute = parsedPlatform == Platform.WINDOWS ? path.matches("[A-Za-z]:\\\\.*") : path.startsWith("/");
            if (!absolute) {
                throw new BadRequestException("Path '" + path + "' must be absolute");
            }
        }
        boolean collectContainers = parsedPlatform == Platform.DOCKER && (containers == null || containers);
        if (parsedPlatform != Platform.KUBERNETES && cleanPaths.isEmpty() && !collectContainers) {
            throw new BadRequestException("Add at least one log path to collect");
        }

        String pattern = null;
        if (parsedMultiline == Multiline.CUSTOM) {
            if (multilinePattern == null || multilinePattern.isBlank() || multilinePattern.length() > 200) {
                throw new BadRequestException("multilinePattern must be 1-200 characters");
            }
            if (multilinePattern.contains("'") || multilinePattern.contains("\n")) {
                throw new BadRequestException("multilinePattern cannot contain quotes or line breaks");
            }
            try {
                Pattern.compile(multilinePattern);
            } catch (PatternSyntaxException e) {
                throw new BadRequestException("multilinePattern is not a valid regular expression");
            }
            pattern = multilinePattern;
        }

        String cleanServer = serverUrl == null ? null : serverUrl.trim().replaceAll("/+$", "");
        if (cleanServer != null && !cleanServer.isEmpty()) {
            try {
                URI uri = URI.create(cleanServer);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getQuery() != null || uri.getFragment() != null || cleanServer.contains("'")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("serverUrl must be an http(s) URL such as https://logs.example.com");
            }
        } else {
            cleanServer = null;
        }

        String servers = null;
        String topic = null;
        if (parsedTransport == Transport.KAFKA) {
            servers = kafkaServers == null ? "" : kafkaServers.replace(" ", "");
            topic = kafkaTopic == null ? "" : kafkaTopic.trim();
            if (!KAFKA_SERVERS.matcher(servers).matches()) {
                throw new BadRequestException("kafkaServers must look like host:9092[,host2:9092]");
            }
            if (!KAFKA_TOPIC.matcher(topic).matches()) {
                throw new BadRequestException("kafkaTopic may only contain letters, digits, '.', '_' and '-'");
            }
        }
        return new AgentOptions(parsedPlatform, parsedTransport, cleanPaths, collectContainers, parsedMultiline,
                pattern, cleanServer, servers, topic);
    }

    /**
     * The options an agent on {@code platform} runs with under a store's collect config: Windows hosts collect the
     * Windows paths, Linux and Docker hosts the other paths.
     *
     * @throws BadRequestException when the config has nothing to collect on this platform
     */
    static AgentOptions of(CollectConfig config, Platform platform, String serverUrl) {
        List<String> paths = platform == Platform.WINDOWS ? config.windowsPaths() : config.paths();
        if (paths.isEmpty() && (platform == Platform.LINUX || platform == Platform.WINDOWS)) {
            throw new BadRequestException("The collect config has no " + (platform == Platform.WINDOWS ? "Windows"
                    : "Linux") + " log paths; add them to the store's collect config first");
        }
        return of(platform.name(), config.transport(), paths, config.containers(), config.multiline(),
                config.multilinePattern(), serverUrl, config.kafkaServers(), config.kafkaTopic());
    }

    /**
     * Validates and normalizes a collect config as entered on the page. Either path list may be empty: hosts of that
     * kind then cannot be deployed until paths are added.
     *
     * @throws BadRequestException when a value is unsafe to put in a config
     */
    static CollectConfig validate(CollectConfig config) {
        // Kubernetes ignores paths, so this checks the settings shared by all platforms.
        AgentOptions shared = of(Platform.KUBERNETES.name(), config.transport(), List.of(), false,
                config.multiline(), config.multilinePattern(), null, config.kafkaServers(), config.kafkaTopic());
        List<String> paths = config.paths().isEmpty() ? List.of()
                : of(Platform.LINUX.name(), "http", config.paths(), false, null, null, null, null, null).paths();
        List<String> windowsPaths = config.windowsPaths().isEmpty() ? List.of()
                : of(Platform.WINDOWS.name(), "http", config.windowsPaths(), false, null, null, null, null, null)
                .paths();
        String name = shared.transport().name().toLowerCase(Locale.ROOT);
        return new CollectConfig(config.storeId(), paths, windowsPaths, config.containers(),
                shared.multiline().name().toLowerCase(Locale.ROOT), shared.multilinePattern(), name,
                shared.kafkaServers(), shared.kafkaTopic(), config.updatedAt());
    }

    /**
     * @return a copy using {@code fallback} when no server URL was given
     */
    AgentOptions withDefaultServer(String fallback) {
        return serverUrl != null ? this : new AgentOptions(platform, transport, paths, containers, multiline,
                multilinePattern, fallback, kafkaServers, kafkaTopic);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String name) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(name + " '" + value + "' is not supported");
        }
    }
}
