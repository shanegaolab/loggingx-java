package com.loggingx.biz.ai;

import com.loggingx.biz.ai.StackTraces.Frame;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Local copies of the stores' code repositories, kept with the {@code git} command (which must be installed): a
 * shallow clone of one branch, updated before an analysis. HTTPS repositories authenticate with an access token sent
 * as a request header on each command, so it is never written to the clone's configuration.
 */
@Component
public class CodeRepositories {

    /** Folders that hold dependencies or build output rather than the project's code. */
    private static final Set<String> SKIPPED_FOLDERS = Set.of(".git", "node_modules", "target", "build", "dist",
            "out", ".gradle", ".idea", ".venv", "venv", "__pycache__", "vendor", ".next");
    private static final long MAX_FILE_BYTES = 1_000_000;

    private final Path root;
    private final Map<Long, FileIndex> indexes = new ConcurrentHashMap<>();

    public CodeRepositories(@Value("${loggingx.ai.repo-dir:${user.home}/.loggingx/repos}") String root) {
        this.root = Path.of(root).toAbsolutePath();
    }

    public static class RepoException extends RuntimeException {
        public RepoException(String message) {
            super(message);
        }
    }

    /**
     * @param commit the checked-out commit
     * @param files  source files found
     */
    public record SyncResult(String commit, int files) {
    }

    /**
     * Code sent to the model.
     *
     * @param startLine first line of {@code text}
     * @param focusLine the line the stack frame points to, or 0
     */
    public record CodeRef(String path, int startLine, int focusLine, String frame, String text) {
    }

    private record FileIndex(String commit, List<String> paths) {
    }

    Path dir(long storeId) {
        return root.resolve("store-" + storeId);
    }

    /**
     * Clones the repository, or updates the clone to the branch's latest commit.
     *
     * @param branch   {@code null} for the repository's default branch
     * @param username {@code null} for a token-only login
     * @param token    {@code null} for a public repository or a {@code file:} URL
     * @throws RepoException with git's message (the token removed) when it fails
     */
    public synchronized SyncResult sync(long storeId, String url, String branch, String username, String token) {
        Path dir = dir(storeId);
        List<String> auth = authArgs(url, username, token);
        boolean cloned = Files.isDirectory(dir.resolve(".git"));
        if (cloned) {
            String origin = git(dir, List.of(), List.of("remote", "get-url", "origin"), token).trim();
            if (!origin.equals(url)) {
                delete(storeId);
                cloned = false;
            }
        }
        if (cloned) {
            git(dir, auth, List.of("fetch", "--depth", "1", "origin", branch == null ? "HEAD" : branch), token);
            git(dir, List.of(), List.of("reset", "--hard", "FETCH_HEAD"), token);
        } else {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new RepoException("Cannot create " + root + ": " + e.getMessage());
            }
            List<String> clone = new ArrayList<>(List.of("clone", "--depth", "1", "--single-branch"));
            if (branch != null) {
                clone.addAll(List.of("--branch", branch));
            }
            clone.addAll(List.of("--", url, dir.toString()));
            git(root, auth, clone, token);
        }
        String commit = git(dir, List.of(), List.of("rev-parse", "HEAD"), token).trim();
        FileIndex index = new FileIndex(commit, listFiles(dir));
        indexes.put(storeId, index);
        return new SyncResult(commit, index.paths().size());
    }

    /**
     * @return whether the store's repository was cloned
     */
    public boolean exists(long storeId) {
        return Files.isDirectory(dir(storeId).resolve(".git"));
    }

    /**
     * Removes the clone, e.g. when the store's repository is removed.
     */
    public synchronized void delete(long storeId) {
        indexes.remove(storeId);
        Path dir = dir(storeId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                // Git marks its objects read-only, which Windows refuses to delete.
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Finds the frames' files in the clone and takes the code around each frame's line.
     *
     * @param maxRefs  at most this many snippets
     * @param context  lines above and below the frame's line
     * @param maxChars all snippets together are cut at this length
     */
    public List<CodeRef> snippets(long storeId, List<Frame> frames, int maxRefs, int context, int maxChars) {
        if (!exists(storeId)) {
            return List.of();
        }
        Path dir = dir(storeId);
        List<String> paths = indexes.computeIfAbsent(storeId, id -> new FileIndex(null, listFiles(dir))).paths();
        List<CodeRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int chars = 0;
        for (Frame frame : frames) {
            if (refs.size() >= maxRefs || chars >= maxChars) {
                break;
            }
            String path = locate(paths, frame.probablePath());
            if (path == null) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(dir.resolve(path), StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                continue;
            } catch (IOException e) {
                continue;
            }
            int focus = frame.line() > 0 && frame.line() <= lines.size() ? frame.line() : 0;
            int start = focus == 0 ? 1 : Math.max(1, focus - context);
            int end = focus == 0 ? Math.min(lines.size(), 2 * context) : Math.min(lines.size(), focus + context);
            if (!seen.add(path + ":" + start + "-" + end)) {
                continue;
            }
            String text = String.join("\n", lines.subList(start - 1, end));
            if (chars + text.length() > maxChars) {
                text = text.substring(0, Math.max(0, maxChars - chars));
            }
            chars += text.length();
            refs.add(new CodeRef(path, start, focus, frame.label(), text));
        }
        return refs;
    }

    /**
     * Finds files named after an identifier the question mentions ({@code OrderService} matches
     * {@code OrderService.java}, {@code pricing.py} matches itself) and takes the lines around its first mention in
     * them.
     *
     * @return at most {@code max} snippets of at most {@code lines} lines
     */
    public List<CodeRef> mentions(long storeId, String identifier, int max, int lines) {
        if (!exists(storeId) || identifier == null || identifier.length() < 3) {
            return List.of();
        }
        Path dir = dir(storeId);
        List<String> paths = indexes.computeIfAbsent(storeId, id -> new FileIndex(null, listFiles(dir))).paths();
        String wanted = identifier.toLowerCase(Locale.ROOT);
        String simple = wanted.contains(".") && !wanted.matches(".*\\.(java|kt|py|js|ts|go|vue|cs|php|rb)$")
                ? wanted.substring(wanted.lastIndexOf('.') + 1) : wanted;
        List<CodeRef> refs = new ArrayList<>();
        for (String path : paths) {
            if (refs.size() >= max) {
                break;
            }
            String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            if (!name.equals(simple) && !stem.equals(simple)) {
                continue;
            }
            List<String> text;
            try {
                text = Files.readAllLines(dir.resolve(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            int focus = 0;
            for (int i = 0; i < text.size(); i++) {
                if (text.get(i).toLowerCase(Locale.ROOT).contains(stem)) {
                    focus = i + 1;
                    break;
                }
            }
            int start = Math.max(1, focus - lines / 4);
            int end = Math.min(text.size(), start + lines - 1);
            refs.add(new CodeRef(path, start, focus, identifier, String.join("\n", text.subList(start - 1, end))));
        }
        return refs;
    }

    /**
     * @return the repository file the trace's path names: the longest tail of the path that some file ends with,
     * preferring the shortest such file
     */
    static String locate(List<String> paths, String probable) {
        if (probable == null || probable.isBlank()) {
            return null;
        }
        String[] segments = probable.replace('\\', '/').replaceAll("^/+", "").split("/");
        for (int from = 0; from < segments.length; from++) {
            String tail = String.join("/", Arrays.copyOfRange(segments, from, segments.length));
            String match = paths.stream()
                    .filter(path -> path.equals(tail) || path.endsWith("/" + tail))
                    .min(Comparator.comparingInt(String::length))
                    .orElse(null);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static List<String> listFiles(Path dir) {
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs) {
                    return !folder.equals(dir) && SKIPPED_FOLDERS.contains(folder.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() <= MAX_FILE_BYTES) {
                        files.add(dir.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static List<String> authArgs(String url, String username, String token) {
        if (token == null || token.isEmpty() || !url.startsWith("http")) {
            return List.of();
        }
        String user = username == null || username.isBlank() ? "oauth2" : username.trim();
        String basic = Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
        return List.of("-c", "http.extraHeader=Authorization: Basic " + basic);
    }

    private static String git(Path dir, List<String> auth, List<String> args, String token) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(auth);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "");
        builder.environment().put("LC_ALL", "C");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new RepoException("Cannot run git (is it installed on the LoggingX server?): " + e.getMessage());
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new RepoException("git " + args.get(0) + " timed out");
            }
        } catch (IOException e) {
            throw new RepoException("git " + args.get(0) + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new RepoException("Interrupted");
        }
        if (process.exitValue() != 0) {
            String message = output.strip();
            if (token != null && !token.isEmpty()) {
                message = message.replace(token, "***");
            }
            message = message.replaceAll("Authorization: Basic \\S+", "Authorization: ***");
            throw new RepoException("git " + args.get(0) + " failed: "
                    + (message.length() > 600 ? message.substring(message.length() - 600) : message));
        }
        return output;
    }
}
