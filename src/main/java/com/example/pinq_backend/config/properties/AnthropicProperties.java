package com.example.pinq_backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic Claude API 설정.
 * application.properties: anthropic.api-key / anthropic.model
 *
 * 용도: 퀴즈 정답 cross-model 검증.
 * 생성은 OpenAI(gpt-4o-mini)가 담당하고, 검증만 Claude가 수행한다.
 * 같은 모델의 correlated bias로 인한 검증 누락(예: "환율 상승 → 수입 증가"
 * 같은 인과 오류를 생성·검증 모두 통과)을 방지하는 것이 목적이다.
 */
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
    String apiKey,
    String model
) {}
