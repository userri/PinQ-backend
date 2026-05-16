package com.example.pinq_backend.news.client;

import com.example.pinq_backend.config.properties.OpenAIProperties;
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
 * OpenAI Chat Completions API 클라이언트.
 *
 * POST https://api.openai.com/v1/chat/completions
 *
 * 뉴스 제목 + 본문을 입력으로 받아 4지선다 퀴즈 JSON을 반환한다.
 */
@Slf4j
@Component
public class OpenAIQuizClient {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_TOKENS = 1024;

    private final RestClient restClient;
    private final OpenAIProperties props;
    private final ObjectMapper objectMapper;

    public OpenAIQuizClient(OpenAIProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 뉴스 기사로부터 퀴즈를 생성한다.
     * 생성 후 별도 검증 호출로 정답의 경제학적 정확성을 확인한다.
     *
     * @param title   뉴스 제목
     * @param content 뉴스 본문 (스크래핑 성공 시 전문, 실패 시 description 스니펫)
     * @return 생성된 퀴즈 DTO. 오류 또는 검증 실패 시 Optional.empty().
     */
    public Optional<GeneratedQuizDto> generateQuiz(String title, String content) {
        Map<String, Object> requestBody = Map.of(
                "model", props.model(),
                "max_tokens", MAX_TOKENS,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", userPrompt(title, content))
                )
        );

        try {
            String rawResponse = restClient.post()
                    .uri(OPENAI_API_URL)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            Optional<GeneratedQuizDto> quizOpt = parseQuiz(rawResponse);
            if (quizOpt.isEmpty()) return Optional.empty();

            // 생성된 퀴즈의 정답을 별도 호출로 독립 검증
            GeneratedQuizDto quiz = quizOpt.get();
            if (!verifyAnswer(quiz)) {
                log.warn("정답 검증 실패, 퀴즈 폐기. question={}", quiz.getQuestion());
                return Optional.empty();
            }

            return Optional.of(quiz);
        } catch (Exception e) {
            log.error("OpenAI API 퀴즈 생성 실패. title={}", title, e);
            return Optional.empty();
        }
    }

    /**
     * 생성된 퀴즈의 정답을 별도 API 호출로 독립 검증한다.
     *
     * 생성 호출과 컨텍스트를 완전히 분리하여,
     * AI가 자신의 이전 답변에 bias 없이 순수하게 경제 사실만 판단하게 한다.
     *
     * @return true면 정답 신뢰 가능, false면 폐기
     */
    private boolean verifyAnswer(GeneratedQuizDto quiz) {
        String answerContent = quiz.getChoices().stream()
                .filter(GeneratedQuizDto.ChoiceDto::isAnswer)
                .map(GeneratedQuizDto.ChoiceDto::getContent)
                .findFirst()
                .orElse("");

        String verifyPrompt = """
                다음 경제 퀴즈의 정답이 경제학적으로 올바른지 판단하세요.
                이전 맥락 없이 오직 경제 지식만으로 판단하세요.

                문제: %s
                정답으로 표시된 보기: %s

                이 정답이 경제학 교과서 기준으로 명확히 옳으면 {"valid": true},
                틀렸거나 불확실하면 {"valid": false, "reason": "이유"} 를 반환하세요.
                JSON만 반환하고 다른 텍스트는 금지입니다.
                """.formatted(quiz.getQuestion(), answerContent);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", props.model(),
                    "max_tokens", 128,
                    "messages", List.of(
                            Map.of("role", "user", "content", verifyPrompt)
                    )
            );

            String rawResponse = restClient.post()
                    .uri(OPENAI_API_URL)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("choices").get(0).path("message").path("content").asText();
            String json = text.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            JsonNode result = objectMapper.readTree(json);
            boolean valid = result.path("valid").asBoolean(true);
            if (!valid) {
                log.warn("검증 실패 이유: {}", result.path("reason").asText());
            }
            return valid;
        } catch (Exception e) {
            // 검증 자체가 실패하면 안전하게 통과시킴 (과도한 폐기 방지)
            log.warn("정답 검증 호출 실패, 통과 처리. question={}", quiz.getQuestion());
            return true;
        }
    }

    private Optional<GeneratedQuizDto> parseQuiz(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("choices").get(0).path("message").path("content").asText();

            // 코드블록이 붙을 수 있어 제거
            String json = text.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            GeneratedQuizDto quiz = objectMapper.readValue(json, GeneratedQuizDto.class);

            if (quiz.isSkip()) {
                log.info("OpenAI가 기사 SKIP 판정. 이유: {}", quiz.getSkipReason());
                return Optional.empty();
            }

            return Optional.of(quiz);
        } catch (Exception e) {
            log.error("OpenAI 응답 파싱 실패. response={}", rawResponse, e);
            return Optional.empty();
        }
    }

    private String systemPrompt() {
        return """
            당신은 경제·금융 개념 교육용 퀴즈 출제 전문가입니다.

            ## 역할
            뉴스 기사를 보고 ① 퀴즈 출제 적합 여부를 먼저 판단한 뒤,
            ② 적합하면 경제 개념 중심의 퀴즈를 만들고, 부적합하면 SKIP을 반환합니다.

            ## SKIP 기준 (아래 중 하나라도 해당하면 SKIP)
            - 특정 기업·브랜드·상품을 홍보하는 내용 (예: "A사의 신제품 특징은?")
            - 특정 인물의 발언·의견·전망을 묻는 내용 (예: "○○ 회장이 말한 것은?")
            - 독자에게 생소할 수 있는 전문 고유명사·약어가 설명 없이 핵심으로 사용된 내용 (예: KDDX, 특정 법안 번호 등)
            - 사건의 단순 수치(인원수, 날짜, 금액)를 묻는 내용
            - 사설·칼럼 등 필자의 주관적 분석이 주를 이루는 기사

            ## 좋은 퀴즈 기준
            - 금리·환율·증시·부동산 등 경제 원리나 개념을 학습할 수 있는 문제
            - 기사가 계기가 되어 독자가 경제 지식을 넓힐 수 있는 문제

            ## 정답 검증 (출제 후 반드시 수행)
            정답 보기를 확정하기 전, 다음을 자문하세요:
            "이 정답은 경제학 교과서 기준으로 반박의 여지 없이 옳은가?"
            조금이라도 불확실하면 해당 문제를 만들지 말고 다른 각도로 출제하거나 SKIP하세요.

            ## 응답 형식 (반드시 JSON만, 다른 텍스트 절대 금지)

            SKIP인 경우:
            {"skip": true, "skipReason": "특정 인물의 발언을 묻는 내용으로 경제 개념 학습과 무관함"}

            퀴즈 생성인 경우:
            {
              "skip": false,
              "question": "퀴즈 문제 (개념을 묻는 질문)",
              "choices": [
                {"orderNum": 1, "content": "보기1", "isAnswer": false},
                {"orderNum": 2, "content": "보기2", "isAnswer": true},
                {"orderNum": 3, "content": "보기3", "isAnswer": false},
                {"orderNum": 4, "content": "보기4", "isAnswer": false}
              ],
              "explanation": "정답 해설. 해당 경제 개념을 쉽게 풀어서 설명 (2~3문장)",
              "keyword": "핵심 경제 용어: 한 줄 개념 설명"
            }
            """;
    }

    private String userPrompt(String title, String content) {
        return """
            다음 뉴스 기사를 검토하고, SKIP 여부를 먼저 판단한 뒤 퀴즈를 출제하거나 SKIP을 반환하세요.

            뉴스 제목: %s
            뉴스 내용: %s

            퀴즈 출제 시 주의사항:
            - 문제는 뉴스의 특정 사실(숫자, 인명, 고유명사)이 아닌 경제 '개념'을 묻어야 합니다
            - 이 기사가 계기가 되어 독자가 경제 원리를 배울 수 있는 방향으로 출제하세요
            - 오답 보기는 그럴듯하지만 명확히 틀린 내용이어야 합니다
            - 한국어로 작성하세요
            - 문제에 경제 변수를 사용할 때는 반드시 방향을 명시하세요 (예: "환율이 상승하면", "금리가 인하되면"). "환율 변동" 같은 방향성 없는 표현은 사용하지 마세요
            """.formatted(title, content);
    }
}