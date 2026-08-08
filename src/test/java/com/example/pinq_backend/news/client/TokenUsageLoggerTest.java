package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TokenUsageLoggerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void openAI_usage_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"prompt_tokens": 5000, "completion_tokens": 300, "total_tokens": 5300}}
                """);
        assertThat(TokenUsageLogger.format("generate", root))
                .isEqualTo("token-usage kind=generate prompt=5000 completion=300 total=5300");
    }

    @Test
    void anthropic_usage_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"input_tokens": 4200, "output_tokens": 120}}
                """);
        assertThat(TokenUsageLogger.format("verify", root))
                .isEqualTo("token-usage kind=verify prompt=4200 completion=120 total=4320");
    }

    @Test
    void usage_노드_없으면_null() throws Exception {
        assertThat(TokenUsageLogger.format("generate", om.readTree("{}"))).isNull();
    }
}
