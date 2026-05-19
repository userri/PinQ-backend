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
 * key 구조 (변경): refresh:{token}  →  value: userId(String)  →  TTL: 30일(기본)
 *
 * 이전 구조(refresh:{userId} → token)에서 token을 key로 역전시켰다.
 * 이렇게 하면:
 *  - /refresh, /logout API에서 클라이언트가 userId를 보낼 필요 없음 → API 단순화
 *  - token 자체가 인증 수단이므로 userId 추측 공격 경로 제거
 *
 * Rotation 전략: 재발급 시 기존 token 키를 삭제하고 새 token을 저장한다.
 * 이미 사용된 refresh token은 Redis에 없으므로 자동으로 거부된다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-expiration-sec:2592000}")
    private long refreshExpirationSec;

    /**
     * 새 refresh token을 발급하고 Redis에 저장한다.
     *
     * @return 발급된 refresh token (UUID)
     */
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                key(token),
                userId.toString(),
                refreshExpirationSec,
                TimeUnit.SECONDS
        );
        return token;
    }

    /**
     * refresh token으로 userId를 조회한다.
     * 유효하지 않거나 만료된 경우 empty를 반환한다.
     */
    public java.util.Optional<Long> resolveUserId(String token) {
        if (token == null) return java.util.Optional.empty();
        String stored = redisTemplate.opsForValue().get(key(token));
        if (stored == null) return java.util.Optional.empty();
        return java.util.Optional.of(Long.parseLong(stored));
    }

    /**
     * refresh token을 삭제한다 (로그아웃 / rotation 시 사용).
     * 존재하지 않는 token이면 아무 일도 일어나지 않는다.
     */
    public void delete(String token) {
        if (token != null) redisTemplate.delete(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
