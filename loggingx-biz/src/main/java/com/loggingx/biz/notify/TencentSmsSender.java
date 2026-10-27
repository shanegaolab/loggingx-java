package com.loggingx.biz.notify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tencent Cloud SMS ({@code SendSms}, API version 2021-01-11), signed with TC3-HMAC-SHA256. The template's variables
 * are positional: {@code {1}} level, {@code {2}} rule, {@code {3}} target, {@code {4}} status, {@code {5}} detail.
 */
public class TencentSmsSender implements SmsSender {

    static final String DEFAULT_ENDPOINT = "https://sms.tencentcloudapi.com";
    private static final String SERVICE = "sms";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    /** Tencent Cloud limits template variables to a few dozen characters, depending on their type. */
    private static final int MAX_VARIABLE = 20;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final String region;
    private final String secretId;
    private final String secretKey;
    private final String sdkAppId;
    private final String signName;
    private final String templateId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * @param endpoint {@code null} for the public endpoint
     * @param region   e.g. {@code ap-guangzhou}
     */
    public TencentSmsSender(String endpoint, String region, String secretId, String secretKey, String sdkAppId,
                            String signName, String templateId) {
        this.endpoint = URI.create(endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT
                : endpoint.replaceAll("/+$", ""));
        this.region = region == null || region.isBlank() ? "ap-guangzhou" : region;
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.sdkAppId = sdkAppId;
        this.signName = signName;
        this.templateId = templateId;
    }

    @Override
    public void send(List<String> phones, SmsParams params) {
        ObjectNode payload = JSON.createObjectNode();
        var numbers = payload.putArray("PhoneNumberSet");
        phones.forEach(phone -> numbers.add(SmsSender.normalize(phone, true)));
        payload.put("SmsSdkAppId", sdkAppId);
        payload.put("SignName", signName);
        payload.put("TemplateId", templateId);
        var values = payload.putArray("TemplateParamSet");
        for (String value : List.of(params.level(), params.rule(), params.target(), params.status(),
                params.detail())) {
            values.add(SmsSender.cut(value, MAX_VARIABLE));
        }
        String body = JSON.writeValueAsString(payload);
        long timestamp = Instant.now().getEpochSecond();
        String host = host(endpoint);

        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", authorization(SERVICE, host, "SendSms", body, timestamp, secretId,
                            secretKey))
                    .header("Content-Type", CONTENT_TYPE)
                    .header("X-TC-Action", "SendSms")
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("X-TC-Version", "2021-01-11")
                    .header("X-TC-Region", region)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SmsException("Cannot reach Tencent Cloud SMS: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("Interrupted");
        }
        JsonNode result;
        try {
            result = JSON.readTree(response.body()).path("Response");
        } catch (RuntimeException e) {
            throw new SmsException("Unexpected answer from Tencent Cloud SMS (HTTP " + response.statusCode() + ")");
        }
        if (result.has("Error")) {
            throw new SmsException("Tencent Cloud SMS refused: " + result.path("Error").path("Code").asString("")
                    + " " + result.path("Error").path("Message").asString(""));
        }
        for (JsonNode status : result.path("SendStatusSet")) {
            if (!"Ok".equals(status.path("Code").asString(""))) {
                throw new SmsException("Tencent Cloud SMS refused " + status.path("PhoneNumber").asString("")
                        + ": " + status.path("Code").asString("") + " " + status.path("Message").asString(""));
            }
        }
    }

    /**
     * The TC3-HMAC-SHA256 {@code Authorization} header of a JSON POST to a Tencent Cloud API.
     *
     * @param action the signed {@code X-TC-Action}, or {@code null} to sign only the content type and host
     */
    static String authorization(String service, String host, String action, String body, long timestamp,
                                String secretId, String secretKey) {
        return signed(service, host, action, sha256Hex(body), timestamp, secretId, secretKey);
    }

    /**
     * @param payloadHash SHA-256 of the request body, hex
     */
    static String signed(String service, String host, String action, String payloadHash, long timestamp,
                         String secretId, String secretKey) {
        String signedHeaders = action == null ? "content-type;host" : "content-type;host;x-tc-action";
        String date = DATE.format(Instant.ofEpochSecond(timestamp));
        String scope = date + "/" + service + "/tc3_request";
        String stringToSign = stringToSign(service, host, action, payloadHash, timestamp);
        byte[] key = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, service);
        key = hmac(key, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /**
     * The string the TC3 signature signs: algorithm, timestamp, credential scope and the canonical request's hash.
     */
    static String stringToSign(String service, String host, String action, String payloadHash, long timestamp) {
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\nhost:" + host + "\n"
                + (action == null ? "" : "x-tc-action:" + action.toLowerCase(java.util.Locale.ROOT) + "\n");
        String signedHeaders = action == null ? "content-type;host" : "content-type;host;x-tc-action";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = DATE.format(Instant.ofEpochSecond(timestamp)) + "/" + service + "/tc3_request";
        return "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
    }

    static String host(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return uri.getHost() + (defaultPort ? "" : ":" + port);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
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
}
