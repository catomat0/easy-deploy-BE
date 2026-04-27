package com.easydeploy.backend.global.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private static final String SECRET =
            "test-jwt-secret-that-is-at-least-64-bytes-long-for-hs512-algorithm-padding-ok";
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 86400000L);
    }

    @Test
    void 토큰_생성_후_이메일_추출() {
        String email = "test@example.com";
        String token = jwtUtil.generateToken(email);
        assertThat(jwtUtil.extractEmail(token)).isEqualTo(email);
    }

    @Test
    void 유효한_토큰_검증_성공() {
        String token = jwtUtil.generateToken("test@example.com");
        assertThat(jwtUtil.isValid(token)).isTrue();
    }

    @Test
    void 만료된_토큰_검증_실패() throws InterruptedException {
        JwtUtil shortLived = new JwtUtil(SECRET, 1L);
        String token = shortLived.generateToken("test@example.com");
        Thread.sleep(10);
        assertThat(shortLived.isValid(token)).isFalse();
    }

    @Test
    void 변조된_토큰_검증_실패() {
        String token = jwtUtil.generateToken("test@example.com");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtUtil.isValid(tampered)).isFalse();
    }

    @Test
    void 빈_문자열_검증_실패() {
        assertThat(jwtUtil.isValid("")).isFalse();
    }

    @Test
    void 임의_문자열_검증_실패() {
        assertThat(jwtUtil.isValid("not.a.jwt.token")).isFalse();
    }

    @Test
    void 다른_키로_서명된_토큰_검증_실패() {
        JwtUtil otherKey = new JwtUtil(
                "completely-different-secret-key-that-is-64-bytes-long-for-hs512-xx", 86400000L);
        String tokenFromOtherKey = otherKey.generateToken("test@example.com");
        assertThat(jwtUtil.isValid(tokenFromOtherKey)).isFalse();
    }
}
