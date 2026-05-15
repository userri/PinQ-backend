package com.example.pinq_backend.news.client;

import com.example.pinq_backend.config.properties.AnthropicProperties;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Claude API 클라이언트.
 *
 * POST https://api.anthropic.com/v1/messages
 *
 * 뉴스 제목 + 내용 요약을 입력으로 받아 4지선다 퀴즈 JSON을 반환한다.
 */
@Slf4j
@Component
public class ClaudeQuizClient {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_TOKENS = 1024;

    private final RestClient restClient;
    private final AnthropicProperties props;
    private final ObjectMapper objectMapper;

    public ClaudeQuizClient(AnthropicProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
            .defaultHeader("x-api-key", props.apiKey())
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader("content-type", "application/json")
            .build();
    }

    /**
     * 뉴스 기사로부터 퀴즈를 생성한다.
     *
     * @param title       뉴스 제목 (HTML 태그 제거 후)
     * @param description 뉴스 내용 요약 (HTML 태그 제거 후)
     * @return 생성된 퀴즈 DTO. 오류 시 Optional.empty().
     */
    public Optional<GeneratedQuizDto> generateQuiz(String title, String description) {
        String prompt = buildPrompt(title, description);

        Map<String, Object> requestBody = Map.of(
            "model", props.model(),
            "max_tokens", MAX_TOKENS,
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            )
        );

        try {
            String rawResponse = restClient.post()
                .uri(CLAUDE_API_URL)
                .body(requestBody)
                .retrieve()
                .body(String.class);

            return parseQuiz(rawResponse);
        } catch (Exception e) {
            log.error("Claude API 퀴즈 생성 실패. title={}", title, e);
            return Optional.empty();
        }
    }

    private Optional<GeneratedQuizDto> parseQuiz(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("content").get(0).path("text").asText();

            // Claude가 JSON만 반환하도록 프롬프트를 설계했지만, 코드블록이 붙을 수 있어 제거
            String json = text.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();

            GeneratedQuizDto quiz = objectMapper.readValue(json, GeneratedQuizDto.class);
            return Optional.of(quiz);
        } catch (Exception e) {
            log.error("Claude 응답 파싱 실패. response={}", rawResponse, e);
            return Optional.empty();
        }
    }

    private String buildPrompt(String title, String description) {
        return """
            당신은 경제·금융 퀴즈 출제 전문가입니다. 아래 뉴스 기사를 바탕으로 4지선다 퀴즈 1개를 만들어주세요.

            뉴스 제목: %s
            뉴스 내용: %s

            반드시 아래 JSON 형식으로만 응답해주세요. 다른 텍스트는 절대 포함하지 마세요:
            {
              "question": "퀴즈 문제 (뉴스 내용에서 직접 답할 수 있어야 함)",
              "choices": [
                {"orderNum": 1, "content": "보기1", "isAnswer": false},
                {"orderNum": 2, "content": "보기2", "isAnswer": true},
                {"orderNum": 3, "content": "보기3", "isAnswer": false},
                {"orderNum": 4, "content": "보기4", "isAnswer": false}
              ],
              "explanation": "정답 해설 (뉴스 내용 근거로 2~3문장)",
              "keyword": "핵심 경제 용어: 한 줄 설명"
            }

            요구사항:
            - 문제는 뉴스 내용에서 직접 답할 수 있어야 합니다
            - 정답은 choices 중 하나에만 isAnswer: true, 나머지는 false
            - 오답 보기는 그럴듯하지만 명확히 틀린 내용이어야 합니다
            - 한국어로 작성하세요
            """.formatted(title, description);
    }
}
