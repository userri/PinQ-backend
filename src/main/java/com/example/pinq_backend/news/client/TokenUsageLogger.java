package com.example.pinq_backend.news.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 응답의 usage(토큰 수)를 grep 가능한 한 줄로 만든다.
 *
 * 목적: axis 실험(스펙 2026-08-08-axis-dedup-design.md 0단계)의 토큰 전후 비교 기준선.
 * OpenAI(prompt_tokens/completion_tokens)와 Anthropic(input_tokens/output_tokens)
 * 양쪽 키를 인식한다. usage 가 없으면 null — 호출부는 로그를 생략한다.
 */
final class TokenUsageLogger {

    private TokenUsageLogger() {}

    static String format(String kind, JsonNode responseRoot) {
        JsonNode usage = responseRoot.path("usage");
        if (usage.isMissingNode() || usage.isNull()) return null;

        int prompt = usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(-1));
        int completion = usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(-1));
        if (prompt < 0 || completion < 0) return null;

        int total = usage.path("total_tokens").asInt(prompt + completion);
        return "token-usage kind=%s prompt=%d completion=%d total=%d"
                .formatted(kind, prompt, completion, total);
    }
}
