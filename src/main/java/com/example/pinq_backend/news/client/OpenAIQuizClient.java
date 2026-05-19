package com.example.pinq_backend.news.client;

import com.example.pinq_backend.article.domain.Category;
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
    private final AnthropicVerifyClient anthropicVerifyClient;
    private final QuizRuleValidator ruleValidator;

    public OpenAIQuizClient(
            OpenAIProperties props,
            ObjectMapper objectMapper,
            AnthropicVerifyClient anthropicVerifyClient,
            QuizRuleValidator ruleValidator
    ) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.anthropicVerifyClient = anthropicVerifyClient;
        this.ruleValidator = ruleValidator;
        this.restClient = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 뉴스 기사로부터 퀴즈를 생성한다.
     *
     * 다단계 검증 흐름:
     *  1. OpenAI로 퀴즈 생성 (system prompt에 카테고리 일치 + 1차원 인과 금지 + 좋은 예시 포함)
     *  2. 룰베이스 검증 (자바, 무료) — 환율↑+수입↑ 같은 명백한 인과 위반 차단
     *  3. Claude cross-model 검증 — 룰북에 없는 미묘한 오답·중복 정답 차단
     *
     * 룰베이스를 Claude 검증보다 먼저 두는 이유: 명백한 오답을 즉시 거르고
     * Claude API 호출 비용을 절감하기 위함.
     *
     * @param title    뉴스 제목
     * @param content  뉴스 본문 (스크래핑 성공 시 전문, 실패 시 description 스니펫)
     * @param category 이 기사가 속한 카테고리. 퀴즈 주제가 이 카테고리와 일치해야 하며,
     *                 기사가 카테고리와 무관하면 SKIP한다.
     * @return 생성된 퀴즈 DTO. 오류 또는 어느 단계든 검증 실패 시 Optional.empty().
     */
    public Optional<GeneratedQuizDto> generateQuiz(String title, String content, Category category) {
        Map<String, Object> requestBody = Map.of(
                "model", props.model(),
                "max_tokens", MAX_TOKENS,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", userPrompt(title, content, category))
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

            GeneratedQuizDto quiz = quizOpt.get();

            // 1차: 룰베이스 검증 — Claude 호출보다 먼저 돌려서 비용 절감.
            QuizRuleValidator.Result ruleResult = ruleValidator.validate(quiz);
            if (!ruleResult.valid()) {
                log.warn("룰베이스 검증 실패, 퀴즈 폐기. reason={} question={}",
                        ruleResult.reason(), quiz.getQuestion());
                return Optional.empty();
            }

            // 2차: Claude cross-model 검증.
            if (!verifyAnswer(quiz)) {
                return Optional.empty();
            }

            return Optional.of(quiz);
        } catch (Exception e) {
            log.error("OpenAI API 퀴즈 생성 실패. title={}", title, e);
            return Optional.empty();
        }
    }

    /**
     * 생성된 퀴즈의 정답을 cross-model로 독립 검증한다.
     *
     * 생성은 OpenAI(gpt-4o-mini), 검증은 Anthropic(Claude).
     * 같은 모델로 생성+검증 시 correlated bias로 인해 동일한 인과 오류
     * (예: "환율 상승 → 수입 증가")를 양쪽 다 통과시키는 문제가 있어,
     * 학습 데이터가 다른 별도 모델을 사용한다.
     *
     * 전체 보기를 포함하여 검증한다.
     * 정답 보기만 전달하면 다른 보기가 경제학적으로 동등하게 옳더라도
     * 검증을 통과하는 문제가 있으므로, 모든 보기를 제공하고
     * "의미상 옳은 보기가 정확히 하나" 임을 조건으로 요구한다.
     *
     * @return true면 정답 신뢰 가능, false면 폐기
     */
    private boolean verifyAnswer(GeneratedQuizDto quiz) {
        String answerContent = quiz.getChoices().stream()
                .filter(GeneratedQuizDto.ChoiceDto::isAnswer)
                .map(GeneratedQuizDto.ChoiceDto::getContent)
                .findFirst()
                .orElse("");

        // 전체 보기 목록을 번호와 함께 구성
        StringBuilder choicesTextBuilder = new StringBuilder();
        for (int i = 0; i < quiz.getChoices().size(); i++) {
            GeneratedQuizDto.ChoiceDto choice = quiz.getChoices().get(i);
            choicesTextBuilder.append(i + 1)
                    .append(". ")
                    .append(choice.getContent());
            if (i < quiz.getChoices().size() - 1) {
                choicesTextBuilder.append("\n");
            }
        }
        String choicesText = choicesTextBuilder.toString();

        String verifyPrompt = """
                다음 경제 퀴즈가 객관식 문항으로서 유효한지 판단하세요.
                이전 맥락 없이 오직 경제 지식만으로 판단하세요.

                문제: %s

                전체 보기:
                %s

                정답으로 표시된 보기: %s

                검증 기준:
                1. 전체 보기 중 경제학 교과서 기준으로 의미상 명확히 옳은 보기가 정확히 하나여야 합니다.
                2. 그 유일하게 옳은 보기가 정답으로 표시된 보기와 같아야 합니다.
                3. 두 개 이상 옳거나, 정답이 틀렸거나, 불확실하면 valid는 false입니다.

                위 기준을 모두 만족하면 {"valid": true},
                만족하지 않으면 {"valid": false, "reason": "이유"} 를 반환하세요.
                JSON만 반환하고 다른 텍스트는 금지입니다.
                """.formatted(quiz.getQuestion(), choicesText, answerContent);

        // Anthropic Claude로 cross-model 검증 위임.
        // HTTP 호출/응답 파싱/fail-open 정책은 AnthropicVerifyClient가 캡슐화한다.
        boolean valid = anthropicVerifyClient.verify(verifyPrompt);
        if (!valid) {
            log.info("Claude 검증 실패로 퀴즈 폐기. question={}", quiz.getQuestion());
        }
        return valid;
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
            뉴스 기사를 보고 ① 퀴즈 출제 적합 여부를 판단하고, ② 적합하면 사용자 메시지에
            지정된 카테고리에 정확히 맞는 경제 개념 퀴즈를 만듭니다. 부적합하면 SKIP.

            ## 핵심 경제 인과 룰북 (정답 판정의 절대 기준)
            아래 인과 방향과 충돌하는 보기는 절대 정답이 될 수 없습니다.

            [환율] — 원/달러, 단기·명목 효과
            - 환율 상승(원화 약세) → 수출 증가, 수입 감소, 수입물가 상승, 외국인 환차손
            - 환율 하락(원화 강세) → 위와 정반대

            [금리]
            - 기준금리 인상 → 대출 수요 감소, 예금 매력 상승, 기존 채권 가격 하락,
              변동금리 이자 부담 증가, 소비·투자 위축
            - 기준금리 인하 → 위와 정반대

            [주식·부동산]
            - 금리 인상 → 주가·부동산 가격 하방 압력 (할인율 상승, 대출 부담 증가)
            - 환율 상승 → 수출주 호재, 내수·수입 의존 업종 악재

            ## SKIP 기준 (하나라도 해당하면 SKIP)
            - 카테고리 불일치: 기사가 사용자 메시지의 지정 카테고리와 직접 관련 없음
              (예: 카테고리가 EXCHANGE_RATE인데 기사가 금리 얘기만 하면 SKIP.
               카테고리가 REAL_ESTATE인데 은행 예금금리 인상 기사면 SKIP.)
            - 특정 기업·브랜드·상품을 홍보하는 내용
            - 특정 인물의 발언·의견·전망을 묻는 내용
            - 독자에게 생소할 수 있는 전문 고유명사·약어가 설명 없이 핵심으로 사용됨
            - 사건의 단순 수치(인원수, 날짜, 금액)를 묻는 내용
            - 사설·칼럼 등 필자의 주관적 분석이 주를 이루는 기사

            ## 금지 출제 패턴 (이전 출제에서 과다 반복되어 학습 가치가 낮음)
            아래 패턴 또는 동치 표현은 절대 사용하지 마세요:
            - "미국 국채 금리 상승 → 주식 시장 매력/투자 매력 감소" 류
            - "변동금리 대출 + 금리 인상 → 이자 부담 증가" 류 (동어반복)
            - "주담대 증가 → 가계부채 증가" 류
            - "공포지수/변동성 지수 상승 → 투자자 매도/위험 회피" 류
            - "X가 상승/인상되면 Y는?" 형식의 단순 1차원 직선 인과
            대신 메커니즘(왜 그렇게 되는지), 정의(개념 자체), 비교(두 개념 차이),
            응용(현실 사례·산업별 차별 영향)을 묻는 문제를 만드세요.

            ## 좋은 문제 예시 (이 방향으로 출제)

            예시 1 — 메커니즘:
            Q. 한국은행이 기준금리를 인상하면 시중은행 예금금리가 따라 오르는 주된 이유는?
            정답: 은행이 시장에서 자금을 조달하는 비용이 올라 예금으로 자금을 끌어와야 하기 때문

            예시 2 — 개념 비교:
            Q. 콜금리와 기준금리의 가장 큰 차이는?
            정답: 콜금리는 은행 간 초단기 대차 시장에서 형성되는 실제 금리이고,
                  기준금리는 한국은행이 정책적으로 결정해 발표하는 금리

            예시 3 — 응용·차별 영향:
            Q. 환율이 가파르게 상승하는 시기에 가장 타격을 받는 기업 유형은?
            정답: 원자재·부품을 해외에서 수입해 국내에서 판매하는 내수 기반 제조업체

            예시 4 — 부동산 메커니즘:
            Q. 기준금리 인상이 아파트 매매가에 하방 압력을 가하는 1차 경로는?
            정답: 주택담보대출 이자 부담 상승으로 매수 수요가 위축되기 때문

            ## 나쁜 문제 예시 (절대 이렇게 출제하지 마세요)
            - Q. 미국 국채 금리가 상승하면 주식시장에는 어떤 영향? (1차원 인과)
            - Q. 금리가 인상되면 대출 수요는? (동어반복)
            - Q. 변동금리 대출에서 금리가 오르면 이자 부담은? (동어반복)

            ## 정답 자가 점검 (출제 직후 반드시 수행)
            1. 정답이 위 "핵심 경제 인과 룰북"과 일치하는가?
            2. 정답 외 3개 보기 중 옳다고 볼 여지가 있는 보기가 있는가? 있으면 폐기.
            3. 문제가 "금지 출제 패턴"에 해당하는가? 해당하면 폐기.
            4. 문제가 지정된 카테고리와 정확히 일치하는가? 아니면 SKIP.
            하나라도 불확실하면 SKIP 또는 재출제.

            ## 응답 형식 (반드시 JSON만, 다른 텍스트 절대 금지)

            SKIP인 경우:
            {"skip": true, "skipReason": "사유"}

            퀴즈 생성인 경우:
            {
              "skip": false,
              "question": "퀴즈 문제 (개념·메커니즘·비교·응용 중 하나)",
              "choices": [
                {"orderNum": 1, "content": "보기1", "isAnswer": false},
                {"orderNum": 2, "content": "보기2", "isAnswer": true},
                {"orderNum": 3, "content": "보기3", "isAnswer": false},
                {"orderNum": 4, "content": "보기4", "isAnswer": false}
              ],
              "explanation": "정답 해설 (2~3문장)",
              "keyword": "핵심 경제 용어: 한 줄 개념 설명"
            }
            """;
    }

    private String userPrompt(String title, String content, Category category) {
        return """
            다음 뉴스 기사를 검토하고, SKIP 여부를 먼저 판단한 뒤 퀴즈를 출제하거나 SKIP을 반환하세요.

            [퀴즈 카테고리]: %s (%s)
            ※ 이 기사로 만들 퀴즈는 반드시 위 카테고리와 직접 관련된 경제 개념을 다뤄야 합니다.
            ※ 기사가 카테고리와 무관하면(예: EXCHANGE_RATE 카테고리인데 기사가 금리 얘기만 함,
              REAL_ESTATE 카테고리인데 은행 예금 기사) 반드시 SKIP하세요.

            뉴스 제목: %s
            뉴스 내용: %s

            출제 시 주의사항:
            - 시스템 프롬프트의 "금지 출제 패턴"을 절대 따라하지 마세요
            - 시스템 프롬프트의 "좋은 문제 예시"처럼 메커니즘·정의·비교·응용 중 하나로 출제하세요
            - 문제는 뉴스의 특정 사실(숫자·인명·고유명사)이 아닌 경제 '개념'을 묻으세요
            - 오답 보기는 그럴듯하지만 명확히 틀린 내용이어야 합니다
            - 한국어로 작성하세요
            - 경제 변수를 사용할 때는 반드시 방향을 명시하세요 ("환율 상승" O, "환율 변동" X)
            """.formatted(category.name(), category.getDisplayName(), title, content);
    }
}
