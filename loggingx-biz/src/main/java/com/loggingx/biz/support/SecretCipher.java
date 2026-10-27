package com.loggingx.biz.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts stored secrets (the backup storage's secret key, the notification channels' passwords) with AES-256-GCM,
 * and signs short-lived tokens, both with a key derived from an application secret ({@code loggingx.backup.secret},
 * the JWT secret by default). Changing that secret makes stored secrets unreadable; they must then be entered again.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final byte[] signingKey;

    public SecretCipher(@Value("${loggingx.backup.secret:${loggingx.security.jwt.secret}}") String secret) {
        byte[] digest = sha256(("loggingx-backup:" + secret).getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(digest, "AES");
        this.signingKey = sha256(("loggingx-backup-token:" + secret).getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @throws IllegalStateException when the text was encrypted with another secret
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || !encrypted.startsWith(PREFIX)) {
            throw new IllegalStateException("The stored secret is not encrypted by this application");
        }
        try {
            byte[] in = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(in, IV_BYTES)));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt the stored secret; the application secret has changed "
                    + "since it was saved. Enter the secret key again.");
        }
    }

    /**
     * @return a token {@code payload.signature} that {@link #verify} accepts
     */
    public String sign(String payload) {
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
    }

    /**
     * @return the payload of a token signed by {@link #sign}, or {@code null} when the signature does not match
     */
    public String verify(String token) {
        int dot = token == null ? -1 : token.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String payload = token.substring(0, dot);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return MessageDigest.isEqual(expected, actual) ? payload : null;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
