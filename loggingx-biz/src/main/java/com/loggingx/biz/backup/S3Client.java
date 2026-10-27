package com.loggingx.biz.backup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A minimal client of the S3 API (put, get, delete an object; check the bucket), signing requests with AWS Signature
 * Version 4. It works with any S3-compatible storage: MinIO, Alibaba Cloud OSS, Tencent Cloud COS, AWS S3, ...
 * <p>
 * Uploads send the file's SHA-256 as the signed payload hash, so the storage verifies what it receives.
 */
public class S3Client {

    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Pattern ERROR_CODE = Pattern.compile("<Code>([^<]*)</Code>");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("<Message>([^<]*)</Message>");

    private final URI endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final boolean pathStyle;
    private final HttpClient http;

    /**
     * @param endpoint  e.g. {@code http://minio:9000} or {@code https://s3.amazonaws.com}
     * @param pathStyle address the bucket in the path ({@code endpoint/bucket/key}) instead of the host name
     */
    public S3Client(String endpoint, String region, String bucket, String accessKey, String secretKey,
                    boolean pathStyle) {
        this.endpoint = URI.create(endpoint.replaceAll("/+$", ""));
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.pathStyle = pathStyle;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * A failed S3 request.
     */
    public static class S3Exception extends RuntimeException {

        private final int status;

        S3Exception(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /**
     * @throws S3Exception when the bucket does not exist or the credentials cannot access it
     */
    public void checkBucket() {
        HttpResponse<String> response = send("HEAD", "", HttpRequest.BodyPublishers.noBody(), EMPTY_SHA256, Map.of(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw failure("HEAD bucket", response.statusCode(), response.body());
        }
    }

    /**
     * Uploads a file.
     *
     * @param sha256 the file's SHA-256, hex
     */
    public void put(String key, Path file, String sha256, String contentType) {
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.ofFile(file);
        } catch (IOException e) {
            throw new S3Exception(0, "Cannot read " + file + ": " + e.getMessage());
        }
        HttpResponse<String> response = send("PUT", key, body, sha256, Map.of("content-type", contentType),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw failure("PUT " + key, response.statusCode(), response.body());
        }
    }

    /**
     * Uploads a small text, e.g. to check that the credentials may write.
     */
    public void putText(String key, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = send("PUT", key, HttpRequest.BodyPublishers.ofByteArray(bytes),
                sha256(bytes), Map.of("content-type", "text/plain; charset=utf-8"),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw failure("PUT " + key, response.statusCode(), response.body());
        }
    }

    /**
     * @return the object's content; the caller closes it
     */
    public InputStream get(String key) {
        HttpResponse<InputStream> response = send("GET", key, HttpRequest.BodyPublishers.noBody(), EMPTY_SHA256,
                Map.of(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String body;
            try (InputStream error = response.body()) {
                body = new String(error.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException e) {
                body = "";
            }
            throw failure("GET " + key, response.statusCode(), body);
        }
        return response.body();
    }

    /**
     * Deletes an object; deleting a missing object succeeds.
     */
    public void delete(String key) {
        HttpResponse<String> response = send("DELETE", key, HttpRequest.BodyPublishers.noBody(), EMPTY_SHA256,
                Map.of(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204 && response.statusCode() != 200 && response.statusCode() != 404) {
            throw failure("DELETE " + key, response.statusCode(), response.body());
        }
    }

    private <T> HttpResponse<T> send(String method, String key, HttpRequest.BodyPublisher body, String payloadHash,
                                     Map<String, String> extraHeaders, HttpResponse.BodyHandler<T> handler) {
        URI uri = objectUri(key);
        Instant now = Instant.now();
        Map<String, String> headers = new TreeMap<>(extraHeaders);
        headers.put("host", hostHeader(uri));
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", AMZ_DATE.format(now));
        String authorization = authorization(method, uri, headers, payloadHash, now, accessKey, secretKey, region);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri).method(method, body)
                .timeout(Duration.ofMinutes(30))
                .header("Authorization", authorization);
        // The client sets Host itself, from the same URI.
        headers.forEach((name, value) -> {
            if (!"host".equals(name)) {
                request.header(name, value);
            }
        });
        try {
            return http.send(request.build(), handler);
        } catch (IOException e) {
            throw new S3Exception(0, "Cannot reach " + endpoint + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new S3Exception(0, "Interrupted");
        }
    }

    URI objectUri(String key) {
        String path = key.isEmpty() ? "" : "/" + encodePath(key);
        String base = endpoint.toString();
        if (pathStyle) {
            return URI.create(base + "/" + encodePath(bucket) + (key.isEmpty() ? "" : path));
        }
        String host = endpoint.getHost();
        String port = endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort();
        return URI.create(endpoint.getScheme() + "://" + bucket + "." + host + port + (key.isEmpty() ? "/" : path));
    }

    static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return uri.getHost() + (defaultPort ? "" : ":" + port);
    }

    /**
     * The {@code Authorization} header of a request without query parameters.
     *
     * @param headers the signed headers, lower-case names (must include {@code host} and {@code x-amz-date})
     */
    static String authorization(String method, URI uri, Map<String, String> headers, String payloadHash,
                                Instant time, String accessKey, String secretKey, String region) {
        Map<String, String> sorted = new TreeMap<>();
        headers.forEach((name, value) -> sorted.put(name.toLowerCase(Locale.ROOT), value.trim()));
        StringBuilder canonicalHeaders = new StringBuilder();
        sorted.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String signedHeaders = String.join(";", sorted.keySet());
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String canonicalRequest = method + "\n" + path + "\n" + "" + "\n" + canonicalHeaders + "\n" + signedHeaders
                + "\n" + payloadHash;

        String date = DATE.format(time);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + AMZ_DATE.format(time) + "\n" + scope + "\n"
                + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /**
     * Encodes each segment of a key as S3 expects (RFC 3986 unreserved characters kept, {@code /} kept).
     */
    static String encodePath(String key) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~' || c == '/') {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static S3Exception failure(String operation, int status, String body) {
        Matcher code = ERROR_CODE.matcher(body == null ? "" : body);
        Matcher message = ERROR_MESSAGE.matcher(body == null ? "" : body);
        String detail = code.find() ? code.group(1) + (message.find() ? ": " + message.group(1) : "")
                : switch (status) {
                    case 403 -> "AccessDenied";
                    case 404 -> "NoSuchBucket or NoSuchKey";
                    default -> "HTTP " + status;
                };
        return new S3Exception(status, operation + " failed (" + status + "): " + detail);
    }
}
