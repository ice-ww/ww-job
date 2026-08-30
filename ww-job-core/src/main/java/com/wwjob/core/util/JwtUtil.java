package com.wwjob.core.util;

/**
 * @author 王威
 * @version 1.0
 */

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 自研 JWT（HMAC-SHA256）。
 * JWT = base64url(header).base64url(payload).base64url(HMAC-SHA256(前两段, secret))
 */
public final class JwtUtil {
    private JwtUtil() {}

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    public static String createToken(String username, String secret, long expireSeconds) {
        long exp = System.currentTimeMillis() / 1000 + expireSeconds;
        String payload = "{\"username\":\"" + escape(username) + "\",\"exp\":" + exp + "}";
        String header = b64url(HEADER.getBytes(StandardCharsets.UTF_8));
        String body = b64url(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        String signature = b64url(hmacBytes(signingInput, secret));
        return signingInput + "." + signature;
    }

    /** 验签 + 过期校验，通过返回 username，否则 null。 */
    public static String parseToken(String token, String secret) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String signingInput = parts[0] + "." + parts[1];
        byte[] expect = decode(parts[2]);
        byte[] actual = hmacBytes(signingInput, secret);
        if (!MessageDigest.isEqual(expect, actual)) return null;   // 恒定时间比较，防时序侧信道
        String payload = new String(decode(parts[1]), StandardCharsets.UTF_8);
        long exp = extractExp(payload);
        if (exp <= System.currentTimeMillis() / 1000) return null; // 已过期
        return extractUsername(payload);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractUsername(String payload) {
        int i = payload.indexOf("\"username\":\"");
        if (i < 0) return null;
        int start = i + "\"username\":\"".length();
        int end = payload.indexOf('"', start);
        if (end < 0) return null;
        return payload.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long extractExp(String payload) {
        int i = payload.indexOf("\"exp\":");
        if (i < 0) return 0;
        int start = i + "\"exp\":".length();
        int end = start;
        while (end < payload.length() && Character.isDigit(payload.charAt(end))) end++;
        if (end == start) return 0;
        return Long.parseLong(payload.substring(start, end));
    }

    private static byte[] hmacBytes(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] decode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
