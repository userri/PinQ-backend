package com.example.pinq_backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JwtTokenProvider 단위 테스트.
 *
 * Spring 컨텍스트 없이 토큰 생성/검증/만료를 직접 검증한다.
 * 만료 테스트는 Clock 을 조작해 Thread.sleep 의존성을 제거했다.
 * CI 환경 지연과 무관하게 결정론적으로 동작한다.
 */
class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-key-must-be-at-least-32-bytes-long!!";

    /**
     * 고정 기준 시각.
     * 파서에도 clock 을 주입하므로 만료 단계(BASE + 주열)가 실제 시스템 시각과 무관하다.
     * 임의의 epoch 전에 속하도록 일부러 과거로 설정해 실제 Clock에 의존하지 않음을 검증한다.
     */
    private static final Instant BASE = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    @DisplayName("생성 직후 토큰은 유효하다")
    void token_isValid_immediately_after_creation() {
        Clock issueClock = Clock.fixed(BASE, ZoneOffset.UTC);
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000L, issueClock);

        String token = provider.createToken(1L, "유리");

        assertThat(provider.isValid(token)).isTrue();
        assertThat(provider.getUserId(token)).isEqualTo(1L);
    }

    @Test
    @DisplayName("만료된 토큰은 isValid() = false 를 반환한다")
    void token_isInvalid_after_expiry() {
        // 발급 시각: BASE / 만료: BASE + 1초
        Clock issueClock = Clock.fixed(BASE, ZoneOffset.UTC);
        JwtTokenProvider issuer = new JwtTokenProvider(SECRET, 1_000L, issueClock);
        String token = issuer.createToken(42L, "유리");

        // 검증 시각: BASE + 2초 (이미 만료)
        Clock laterClock = Clock.fixed(BASE.plusSeconds(2), ZoneOffset.UTC);
        JwtTokenProvider verifier = new JwtTokenProvider(SECRET, 1_000L, laterClock);

        assertThat(verifier.isValid(token)).isFalse();
    }

    @Test
    @DisplayName("만료된 토큰으로 getUserId() 를 호출하면 JwtException 이 발생한다")
    void getUserId_throws_on_expired_token() {
        Clock issueClock = Clock.fixed(BASE, ZoneOffset.UTC);
        JwtTokenProvider issuer = new JwtTokenProvider(SECRET, 1_000L, issueClock);
        String token = issuer.createToken(99L, "유리");

        Clock laterClock = Clock.fixed(BASE.plusSeconds(2), ZoneOffset.UTC);
        JwtTokenProvider verifier = new JwtTokenProvider(SECRET, 1_000L, laterClock);

        org.junit.jupiter.api.Assertions.assertThrows(
                io.jsonwebtoken.JwtException.class,
                () -> verifier.getUserId(token)
        );
    }

    @Test
    @DisplayName("위변조된 토큰은 isValid() = false 를 반환한다")
    void tampered_token_isInvalid() {
        Clock issueClock = Clock.fixed(BASE, ZoneOffset.UTC);
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000L, issueClock);

        String token = provider.createToken(1L, "유리");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThat(provider.isValid(tampered)).isFalse();
    }
}
