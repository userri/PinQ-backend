package com.example.pinq_backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic Claude API 설정.
 * application.properties: anthropic.api-key / anthropic.model
 */
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
    String apiKey,
    String model
) {}
