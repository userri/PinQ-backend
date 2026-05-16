package com.example.pinq_backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 검색 API 인증 정보.
 * application.properties: naver.client-id / naver.client-secret
 */
@ConfigurationProperties(prefix = "naver")
public record NaverNewsProperties(
    String clientId,
    String clientSecret
) {}
