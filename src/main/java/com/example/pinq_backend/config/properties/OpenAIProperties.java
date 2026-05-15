package com.example.pinq_backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI API 설정.
 * application.properties: openai.api-key / openai.model
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAIProperties(
    String apiKey,
    String model
) {}
