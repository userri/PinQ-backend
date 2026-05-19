package com.example.pinq_backend.auth.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh token을 Redis에 저장/조회/삭제한다.
 *
 * key 구조: refresh:{userId}  →  value: refreshToken(UUID)  →  TTL: 30일(기본)
 *
 * Rotation 전략: 재발급 시 기존 토큰을 삭제하고 새 토큰을 저장한다.
 * 이미 사용된 refresh token으로 재발급을 시도하면 Redis에 없으므로 자동으로 거부된다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-expiration-sec:2592000}")
    private long refreshExpirationSec;

    /** 새 refresh token을 발급하고 Redis에 저장한다. */
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                key(userId),
                token,
                refreshExpirationSec,
                TimeUnit.SECONDS
        );
        return token;
    }

    /**
     * refresh token이 유효한지 검증한다.
     * Redis에 저장된 값과 일치해야 true.
     */
    public boolean validate(Long userId, String token) {
        String stored = redisTemplate.opsForValue().get(key(userId));
        return token != null && token.equals(stored);
    }

    /** refresh token을 삭제한다 (로그아웃 / rotation 시 사용). */
    public void delete(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
