package com.example.pinq_backend.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 토큰 생성 및 검증.
 *
 *  - subject: userId (Long → String)
 *  - claim "nickname": 사용자 닉네임 (편의용, 검증 불필요)
 *
 * 만료 검증 단위 테스트를 위해 Clock 주입을 지원한다.
 * Clock 없는 생성자(프로덕션용)도 유지한다.
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;
    private final Clock clock;

    /** 프로덕션 생성자 — Spring 시스템 Clock 사용. */
    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs
    ) {
        this(secret, expirationMs, Clock.systemUTC());
    }

    /** 테스트 생성자 — 임의 Clock 주입 가능. */
    public JwtTokenProvider(String secret, long expirationMs, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.clock = clock;
    }

    /** userId 를 subject 로 하는 JWT 를 생성한다. */
    public String createToken(Long userId, String nickname) {
        Date now = new Date(clock.millis());
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("nickname", nickname)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 토큰의 유효성을 검사하고 userId 를 반환한다.
     *
     * @throws JwtException 토큰이 위변조·만료된 경우
     */
    public Long getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.parseLong(claims.getSubject());
    }

    /** 토큰이 유효하면 true, 그 외 false. */
    public boolean isValid(String token) {
        try {
            getUserId(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
