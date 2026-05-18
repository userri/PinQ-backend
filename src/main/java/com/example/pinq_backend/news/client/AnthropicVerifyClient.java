package com.example.pinq_backend.news.client;

import com.example.pinq_backend.config.properties.AnthropicProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Claude API 클라이언트 (퀴즈 정답 cross-model 검증 전용).
 *
 * POST https://api.anthropic.com/v1/messages
 *
 * OpenAIQuizClient가 생성한 퀴즈의 정답이 경제학적으로 옳은지를
 * 생성과 다른 모델(Claude)로 독립 검증한다.
 *
 * 왜 다른 모델인가:
 *  - 같은 GPT 모델로 생성+검증 시 correlated bias가 발생.
 *    예: "환율 상승 → 수입 증가" 같은 잘못된 인과를 생성도, 검증도 통과시킴.
 *  - 학습 데이터와 RLHF 방식이 다른 Claude는 GPT의 인과 오류를 잡을 가능성이 높음.
 *
 * 실패 시 정책: 검증 자체가 실패하면 true 반환 (fail-open).
 *  - 외부 API 장애로 정상 퀴즈가 과도하게 폐기되는 것을 막기 위함.
 *  - 단, 검증 결과가 명시적으로 false면 폐기.
 */
@Slf4j
@Component
public class AnthropicVerifyClient {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 256;

    private final RestClient restClient;
    private final AnthropicProperties props;
    private final ObjectMapper objectMapper;

    public AnthropicVerifyClient(AnthropicProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", "application/json")
                .build();
    }

    /**
     * 주어진 검증 프롬프트를 Claude로 전송하여 퀴즈 유효성을 확인한다.
     *
     * 프롬프트는 OpenAIQuizClient.verifyAnswer()에서 이미 완성된 형태로 전달받으며,
     * 응답은 {"valid": true/false, "reason"?: "..."} JSON이어야 한다.
     *
     * @param verifyPrompt 검증 지시 + 퀴즈 정보가 포함된 사용자 프롬프트
     * @return true면 정답 신뢰 가능 또는 검증 호출 실패(fail-open), false면 폐기 대상
     */
    public boolean verify(String verifyPrompt) {
        Map<String, Object> requestBody = Map.of(
                "model", props.model(),
                "max_tokens", MAX_TOKENS,
                "messages", List.of(
                        Map.of("role", "user", "content", verifyPrompt)
                )
        );

        try {
            String rawResponse = restClient.post()
                    .uri(ANTHROPIC_API_URL)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(rawResponse);

            // Anthropic 응답 구조: { "content": [ { "type": "text", "text": "..." } ], ... }
            // OpenAI(choices[0].message.content)와 위치가 다르므로 주의.
            String text = root.path("content").get(0).path("text").asText();

            // 모델이 가끔 ```json 코드블록이나 머리말을 붙이므로 JSON 본문만 추출
            String json = extractJson(text);

            JsonNode result = objectMapper.readTree(json);
            boolean valid = result.path("valid").asBoolean(true);
            if (!valid) {
                log.warn("Claude 검증 실패. reason={}", result.path("reason").asText());
            }
            return valid;
        } catch (Exception e) {
            // 검증 호출 자체 실패 시 안전하게 통과 처리 (과도한 폐기 방지)
            log.warn("Claude 검증 호출 실패, fail-open 처리. error={}", e.getMessage());
            return true;
        }
    }

    /**
     * Claude 응답 텍스트에서 JSON 본문만 추출한다.
     * - ```json 코드블록 제거
     * - "Here is the JSON:" 같은 머리말 무시: 첫 '{' 부터 마지막 '}'까지 substring
     */
    private String extractJson(String text) {
        String stripped = text.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();

        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return stripped.substring(start, end + 1);
        }
        return stripped;
    }
}
