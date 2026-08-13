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
                .isEqualTo("token-usage kind=generate prompt=5000 completion=300 cache_write=0 cache_read=0 total=5300");
    }

    @Test
    void anthropic_usage_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"input_tokens": 4200, "output_tokens": 120}}
                """);
        assertThat(TokenUsageLogger.format("verify", root))
                .isEqualTo("token-usage kind=verify prompt=4200 completion=120 cache_write=0 cache_read=0 total=4320");
    }

    /**
     * 캐싱 성과는 이 두 필드로만 잰다 — prompt(=input_tokens)는 캐시에 안 걸린 잔여분이라
     * 캐시가 먹으면 오히려 줄어든다. 실제 프롬프트 크기는 셋의 합인 total 이다.
     */
    @Test
    void anthropic_캐시_적중_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"input_tokens": 900, "output_tokens": 120,
                           "cache_creation_input_tokens": 0, "cache_read_input_tokens": 5200}}
                """);
        assertThat(TokenUsageLogger.format("verify", root))
                .isEqualTo("token-usage kind=verify prompt=900 completion=120 "
                        + "cache_write=0 cache_read=5200 total=6220");
    }

    @Test
    void anthropic_캐시_최초_기록_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"input_tokens": 900, "output_tokens": 120,
                           "cache_creation_input_tokens": 5200, "cache_read_input_tokens": 0}}
                """);
        assertThat(TokenUsageLogger.format("verify", root))
                .isEqualTo("token-usage kind=verify prompt=900 completion=120 "
                        + "cache_write=5200 cache_read=0 total=6220");
    }

    @Test
    void usage_노드_없으면_null() throws Exception {
        assertThat(TokenUsageLogger.format("generate", om.readTree("{}"))).isNull();
    }
}
