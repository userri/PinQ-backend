package com.example.pinq_backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JwtTokenProvider 단위 테스트.
 *
 * Spring 컨텍스트 없이 토큰 생성/검증/만료를 직접 검증한다.
 */
class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-key-must-be-at-least-32-bytes-long!!";

    @Test
    @DisplayName("생성 직후 토큰은 유효하다")
    void token_isValid_immediately_after_creation() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000L); // 60초

        String token = provider.createToken(1L, "유리");

        assertThat(provider.isValid(token)).isTrue();
        assertThat(provider.getUserId(token)).isEqualTo(1L);
    }

    @Test
    @DisplayName("만료된 토큰은 isValid() = false 를 반환한다")
    void token_isInvalid_after_expiry() throws InterruptedException {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 2_000L); // 2초

        String token = provider.createToken(42L, "유리");

        assertThat(provider.isValid(token)).isTrue(); // 발급 직후 → 유효

        Thread.sleep(2_500); // 2.5초 대기 → 만료

        assertThat(provider.isValid(token)).isFalse(); // 만료 후 → 무효
    }

    @Test
    @DisplayName("만료된 토큰으로 getUserId() 를 호출하면 JwtException 이 발생한다")
    void getUserId_throws_on_expired_token() throws InterruptedException {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 2_000L); // 2초

        String token = provider.createToken(99L, "유리");

        Thread.sleep(2_500);

        org.junit.jupiter.api.Assertions.assertThrows(
                io.jsonwebtoken.JwtException.class,
                () -> provider.getUserId(token)
        );
    }

    @Test
    @DisplayName("위변조된 토큰은 isValid() = false 를 반환한다")
    void tampered_token_isInvalid() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000L);

        String token = provider.createToken(1L, "유리");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThat(provider.isValid(tampered)).isFalse();
    }
}
