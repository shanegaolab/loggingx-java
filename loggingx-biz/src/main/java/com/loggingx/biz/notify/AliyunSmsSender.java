package com.loggingx.biz.notify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Alibaba Cloud SMS ({@code SendSms}, API version 2017-05-25), signed with the RPC signature (HMAC-SHA1). The template
 * variables are {@code ${level}}, {@code ${rule}}, {@code ${target}}, {@code ${status}} and {@code ${detail}}.
 */
public class AliyunSmsSender implements SmsSender {

    static final String DEFAULT_ENDPOINT = "https://dysmsapi.aliyuncs.com";
    /** Alibaba Cloud limits a template variable to 35 characters. */
    private static final int MAX_VARIABLE = 30;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String templateCode;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * @param endpoint {@code null} for the public endpoint
     */
    public AliyunSmsSender(String endpoint, String accessKeyId, String accessKeySecret, String signName,
                           String templateCode) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.replaceAll("/+$", "");
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    @Override
    public void send(List<String> phones, SmsParams params) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("level", SmsSender.cut(params.level(), MAX_VARIABLE));
        variables.put("rule", SmsSender.cut(params.rule(), MAX_VARIABLE));
        variables.put("target", SmsSender.cut(params.target(), MAX_VARIABLE));
        variables.put("status", SmsSender.cut(params.status(), MAX_VARIABLE));
        variables.put("detail", SmsSender.cut(params.detail(), MAX_VARIABLE));

        Map<String, String> query = new TreeMap<>();
        query.put("AccessKeyId", accessKeyId);
        query.put("Action", "SendSms");
        query.put("Format", "JSON");
        query.put("RegionId", "cn-hangzhou");
        query.put("SignatureMethod", "HMAC-SHA1");
        query.put("SignatureNonce", UUID.randomUUID().toString());
        query.put("SignatureVersion", "1.0");
        query.put("Timestamp", TIMESTAMP.format(Instant.now()));
        query.put("Version", "2017-05-25");
        query.put("PhoneNumbers", phones.stream().map(phone -> SmsSender.normalize(phone, false))
                .collect(Collectors.joining(",")));
        query.put("SignName", signName);
        query.put("TemplateCode", templateCode);
        query.put("TemplateParam", JSON.writeValueAsString(variables));
        String signature = signature(query, accessKeySecret);
        String url = endpoint + "/?Signature=" + encode(signature) + "&" + canonical(query);

        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SmsException("Cannot reach Alibaba Cloud SMS: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("Interrupted");
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (RuntimeException e) {
            throw new SmsException("Unexpected answer from Alibaba Cloud SMS (HTTP " + response.statusCode() + ")");
        }
        String code = body.path("Code").asString("");
        if (!"OK".equals(code)) {
            throw new SmsException("Alibaba Cloud SMS refused: " + code + " " + body.path("Message").asString(""));
        }
    }

    /**
     * The RPC signature: Base64(HMAC-SHA1(secret + "&", "GET&%2F&" + encode(canonical query))).
     */
    static String signature(Map<String, String> query, String secret) {
        String stringToSign = "GET&" + encode("/") + "&" + encode(canonical(query));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(Map<String, String> query) {
        return new TreeMap<>(query).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * Percent-encoding as Alibaba Cloud signs it: RFC 3986, so spaces are {@code %20}, {@code *} is encoded and
     * {@code ~} is not.
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }
}
