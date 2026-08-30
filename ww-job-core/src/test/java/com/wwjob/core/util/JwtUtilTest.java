package com.wwjob.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author 王威
 * @version 1.0
 */


class JwtUtilTest {
    static final String SECRET = "test-secret";

    @Test
    void createToken_返回三段点分结构() {
        String token = JwtUtil.createToken("admin", SECRET, 3600);
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void parseToken_能取回用户名() {
        String token = JwtUtil.createToken("admin", SECRET, 3600);
        assertEquals("admin", JwtUtil.parseToken(token, SECRET));
    }

    @Test
    void parseToken_过期返回null() {
        // expireSeconds 传负数 → 签发即过期
        String token = JwtUtil.createToken("admin", SECRET, -1);
        assertNull(JwtUtil.parseToken(token, SECRET));
    }

    @Test
    void parseToken_篡改payload返回null() {
        String token = JwtUtil.createToken("admin", SECRET, 3600);
        // 把中间段 payload 的 admin 改成 eve（改后 base64url 重编码）
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + "eyJ1c2VybmFtZSI6ImV2ZSJ9" + "." + parts[2];
        assertNull(JwtUtil.parseToken(tampered, SECRET));
    }

    @Test
    void parseToken_错误secret返回null() {
        String token = JwtUtil.createToken("admin", SECRET, 3600);
        assertNull(JwtUtil.parseToken(token, "wrong-secret"));
    }

    @Test
    void parseToken_垃圾输入返回null() {
        assertNull(JwtUtil.parseToken(null, SECRET));
        assertNull(JwtUtil.parseToken("not-a-token", SECRET));
        assertNull(JwtUtil.parseToken("a.b", SECRET)); // 只有两段
    }
}