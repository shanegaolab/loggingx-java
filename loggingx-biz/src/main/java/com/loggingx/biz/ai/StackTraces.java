package com.loggingx.biz.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an error log: its error type, the stack frames (Java and Kotlin, Python, JavaScript and TypeScript, Go, and
 * file:line references in general) and a fingerprint that groups occurrences of the same error.
 */
public final class StackTraces {

    /** {@code at com.shop.OrderService.submit(OrderService.java:88)}, optionally with a module prefix. */
    private static final Pattern JAVA_FRAME = Pattern.compile(
            "^\\s*at\\s+(?:[\\w.$-]+/)*([\\w$.<>]+)\\.([\\w$<>-]+)\\(([^:()]+?)(?::(\\d+))?\\)", Pattern.MULTILINE);
    /** {@code File "/app/shop/orders.py", line 12, in submit} */
    private static final Pattern PYTHON_FRAME = Pattern.compile(
            "File \"([^\"]+)\", line (\\d+), in (\\S+)");
    /** {@code at submit (/app/src/orders.ts:10:5)} or {@code at /app/src/orders.js:10:5} */
    private static final Pattern JS_FRAME = Pattern.compile(
            "^\\s*at\\s+(?:([\\w$.<> \\[\\]]+?)\\s+\\()?((?:file://)?[^\\s()]+?\\.(?:js|mjs|cjs|jsx|ts|tsx|vue)):(\\d+)(?::\\d+)?\\)?\\s*$",
            Pattern.MULTILINE);
    /** {@code \t/app/orders/service.go:42 +0x1d} */
    private static final Pattern GO_FRAME = Pattern.compile("^\\s*(/[^\\s:]+\\.go):(\\d+)", Pattern.MULTILINE);
    private static final Pattern ERROR_TYPE = Pattern.compile(
            "\\b((?:[A-Za-z_][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error|Throwable|Panic|Fault))\\b");
    private static final Pattern GO_PANIC = Pattern.compile("^panic: (.+)$", Pattern.MULTILINE);

    /** Frames of these packages and folders are libraries, not the application. */
    private static final List<String> LIBRARY_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "kotlinx.", "scala.", "org.springframework.", "org.apache.", "io.netty.", "reactor.",
            "org.hibernate.", "com.zaxxer.", "org.junit.", "jakarta.", "tools.jackson.", "com.fasterxml.",
            "org.mybatis.", "org.eclipse.", "io.undertow.", "com.mysql.", "org.postgresql.", "io.micrometer.",
            "com.google.", "okhttp3.", "feign.", "io.grpc.", "org.slf4j.", "ch.qos.");
    private static final List<String> LIBRARY_PATHS = List.of("site-packages/", "dist-packages/", "node_modules/",
            "/usr/lib/", "/usr/local/lib/", "internal/", "node:", "/go/pkg/mod/", "/usr/local/go/");

    private StackTraces() {
    }

    /**
     * A stack frame.
     *
     * @param className Java or Kotlin class, or {@code null}
     * @param function  method or function, or {@code null}
     * @param file      file name or path as the trace shows it
     * @param line      line number, or 0 when unknown
     * @param library   whether it belongs to a library rather than the application
     */
    public record Frame(String className, String function, String file, int line, boolean library) {

        /**
         * @return the path the file most likely has in a repository: the package as folders for Java and Kotlin
         * (the class's outer class names the file), else the path as shown
         */
        public String probablePath() {
            if (className != null && file != null && !file.contains("/") && !file.contains("\\")) {
                int lastDot = className.lastIndexOf('.');
                return lastDot < 0 ? file : className.substring(0, lastDot).replace('.', '/') + "/" + file;
            }
            String path = file == null ? "" : file.replace('\\', '/');
            return path.startsWith("file://") ? path.substring(7) : path;
        }

        public String label() {
            String place = file + (line > 0 ? ":" + line : "");
            if (className != null) {
                return className + "." + function + "(" + place + ")";
            }
            return function == null ? place : function + " (" + place + ")";
        }
    }

    /**
     * @param errorType the exception or error class, or {@code null}
     * @param headline  the first line of the log
     * @param frames    the frames in trace order (innermost call first for Java, Go and JavaScript; Python lists
     *                  them outermost first)
     */
    public record ParsedError(String errorType, String headline, List<Frame> frames) {

        /**
         * @return the frame the error most likely comes from: the first application frame (the last one for
         * Python), else the first frame
         */
        public Frame location() {
            List<Frame> app = appFrames();
            if (!app.isEmpty()) {
                return app.get(0);
            }
            return frames.isEmpty() ? null : frames.get(0);
        }

        /**
         * @return application frames, most relevant first
         */
        public List<Frame> appFrames() {
            return frames.stream().filter(frame -> !frame.library()).toList();
        }

        /**
         * Groups occurrences of the same error: the error type and the method it was raised in, without line
         * numbers (so the group survives small edits); without a stack trace, the headline with numbers, ids and
         * quoted values removed.
         */
        public String fingerprint() {
            Frame location = location();
            String key;
            if (location != null) {
                String where = location.className() != null ? location.className() + "." + location.function()
                        : location.probablePath() + "#" + location.function();
                key = (errorType == null ? "" : errorType) + "|" + where;
            } else {
                key = (errorType == null ? "" : errorType) + "|" + normalize(headline);
            }
            return sha1(key);
        }
    }

    public static ParsedError parse(String text) {
        String log = text == null ? "" : text;
        String headline = log.lines().findFirst().orElse("").trim();
        List<Frame> frames = new ArrayList<>();

        Matcher java = JAVA_FRAME.matcher(log);
        while (java.find()) {
            String className = java.group(1);
            frames.add(new Frame(className, java.group(2), java.group(3).trim(),
                    java.group(4) == null ? 0 : Integer.parseInt(java.group(4)), isLibraryClass(className)));
        }
        List<Frame> python = new ArrayList<>();
        Matcher py = PYTHON_FRAME.matcher(log);
        while (py.find()) {
            python.add(new Frame(null, py.group(3), py.group(1), Integer.parseInt(py.group(2)),
                    isLibraryPath(py.group(1))));
        }
        // Python prints the innermost call last.
        frames.addAll(python.reversed());
        if (frames.isEmpty()) {
            Matcher js = JS_FRAME.matcher(log);
            while (js.find()) {
                frames.add(new Frame(null, js.group(1) == null ? null : js.group(1).trim(), js.group(2),
                        Integer.parseInt(js.group(3)), isLibraryPath(js.group(2))));
            }
            Matcher go = GO_FRAME.matcher(log);
            while (go.find()) {
                frames.add(new Frame(null, null, go.group(1), Integer.parseInt(go.group(2)),
                        isLibraryPath(go.group(1))));
            }
        }
        return new ParsedError(errorType(log), headline, List.copyOf(frames));
    }

    private static String errorType(String log) {
        // Python names the error on its last line: "ValueError: invalid literal".
        if (log.contains("Traceback (most recent call last)")) {
            List<String> lines = log.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            for (int i = lines.size() - 1; i >= 0; i--) {
                Matcher matcher = Pattern.compile("^([A-Za-z_][\\w.]*)(?::|$)").matcher(lines.get(i));
                if (matcher.find() && !lines.get(i).startsWith("File ")) {
                    return matcher.group(1);
                }
            }
        }
        Matcher panic = GO_PANIC.matcher(log);
        if (panic.find()) {
            return "panic";
        }
        Matcher matcher = ERROR_TYPE.matcher(log);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isLibraryClass(String className) {
        return LIBRARY_PACKAGES.stream().anyMatch(className::startsWith)
                || className.contains("$$") || className.startsWith("$Proxy");
    }

    private static boolean isLibraryPath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return LIBRARY_PATHS.stream().anyMatch(normalized::contains);
    }

    /**
     * @return the line with what varies between occurrences replaced: quoted values, ids, numbers
     */
    static String normalize(String line) {
        String value = line == null ? "" : line;
        value = value.replaceAll("\"[^\"]*\"|'[^']*'", "*");
        value = value.replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", "#");
        value = value.replaceAll("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{12,}\\b", "#");
        value = value.replaceAll("\\d+", "#");
        // Leading timestamps and levels differ in format only.
        value = value.replaceAll("^[#:\\-./ T,+Z\\[\\]]+", "");
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
