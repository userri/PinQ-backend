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

    /**
     * 핵심 경제 인과 룰북. 생성(systemPrompt)과 검증(verifyAnswer) 양쪽이 공유한다.
     *
     * 검증에도 넣는 이유: 운영 데이터 분석에서 환율 방향 오답 5건(예: "환율 하락→수출 증가")이
     * Claude 검증을 통과했는데, 원인이 검증 프롬프트에 방향 기준이 없어 "경제 지식만으로"
     * 판단하게 둔 탓이었다. 생성·검증이 같은 절대 기준을 보도록 단일 출처로 통일한다.
     */
    private static final String CAUSAL_RULEBOOK = """
            [환율] — 원/달러, 단기·명목 효과
            - 환율 상승(원화 약세) → 수출 증가, 수입 감소, 수입물가 상승, 외국인 환차손
            - 환율 하락(원화 강세) → 위와 정반대

            [금리]
            - 기준금리 인상 → 대출 수요 감소, 예금 매력 상승, 기존 채권 가격 하락,
              변동금리 이자 부담 증가, 소비·투자 위축
            - 기준금리 인하 → 위와 정반대

            [주식·부동산]
            - 금리 인상 → 주가·부동산 가격 하방 압력 (할인율 상승, 대출 부담 증가)
            - 환율 상승 → 수출주 호재, 내수·수입 의존 업종 악재""";

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
     *  1. OpenAI로 퀴즈 생성 (system prompt에 카테고리 일치 + 1차원 인과 금지 + 좋은 예시 포함,
     *     user prompt에 최근 출제 이력 — 같은 개념 재출제 회피 유도)
     *  2. 룰베이스 검증 (자바, 무료) — 환율↑+수입↑ 같은 명백한 인과 위반 차단
     *  3. Claude cross-model 검증 — 룰북에 없는 미묘한 오답·중복 정답·이력과의 의미적 중복 차단
     *
     * 룰베이스를 Claude 검증보다 먼저 두는 이유: 명백한 오답을 즉시 거르고
     * Claude API 호출 비용을 절감하기 위함.
     *
     * @param title           뉴스 제목
     * @param content         뉴스 본문 (스크래핑 성공 시 전문, 실패 시 description 스니펫)
     * @param category        이 기사가 속한 카테고리. 퀴즈 주제가 이 카테고리와 일치해야 하며,
     *                        기사가 카테고리와 무관하면 SKIP한다.
     * @param recentQuestions 최근 출제된 문항 목록 (같은 카테고리 + 오늘 생성분).
     *                        생성 프롬프트에는 "중복 금지" 목록으로, Claude 검증에는
     *                        의미적 중복 판정 기준으로 주입된다. 비어 있으면 해당 섹션 생략.
     * @return 생성된 퀴즈 DTO. 오류 또는 어느 단계든 검증 실패 시 Optional.empty().
     */
    public Optional<GeneratedQuizDto> generateQuiz(
            String title,
            String content,
            Category category,
            List<String> recentQuestions
    ) {
        return generateQuiz(title, content, category, recentQuestions, null, null, null, null);
    }

    /**
     * 실험 주입 버전 — dry-run 워크벤치 전용.
     *
     * extraGenRules/extraVerifyRules 는 배포 없이 프롬프트 룰 초안을 검증하기 위한
     * 임시 주입 텍스트, modelOverride 는 생성 모델 A/B 비교용 임시 모델명이다.
     * 셋 다 null 이면 프로덕션 경로와 완전히 동일하게 동작한다.
     * 실험에서 효과가 확인된 것만 코드/설정에 확정 반영한다.
     */
    public Optional<GeneratedQuizDto> generateQuiz(
            String title,
            String content,
            Category category,
            List<String> recentQuestions,
            String extraGenRules,
            String extraVerifyRules,
            String modelOverride,
            String genPromptOverride
    ) {
        // genPromptOverride: 시스템 프롬프트 전면 교체 실험용 (프롬프트 간소화 A/B).
        // 교체 프롬프트는 응답 JSON 형식 지시를 반드시 포함해야 한다 — 파싱이 깨지면 후보 전량 폐기됨.
        String systemContent = (genPromptOverride != null && !genPromptOverride.isBlank())
                ? genPromptOverride
                : systemPrompt(category);
        if (extraGenRules != null && !extraGenRules.isBlank()) {
            systemContent += "\n\n## 실험 규칙 (아래 규칙도 반드시 준수)\n" + extraGenRules;
        }

        Map<String, Object> requestBody = Map.of(
                "model", (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : props.model(),
                "max_tokens", MAX_TOKENS,
                "messages", List.of(
                        Map.of("role", "system", "content", systemContent),
                        Map.of("role", "user", "content", userPrompt(title, content, category, recentQuestions))
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

            // 2차: Claude cross-model 검증 (정답 정합성 + 이력과의 의미적 중복).
            if (!verifyAnswer(quiz, recentQuestions, extraVerifyRules)) {
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
     * 최근 출제 이력이 있으면 "의미적 중복" 판정도 함께 요구한다.
     * 표현이 달라 렉시컬 검사(QuizSimilarityChecker)를 통과하는 개념 중복
     * (예: "환율 상승→수출기업 이점"의 어휘만 다른 변주)을 여기서 잡는다.
     *
     * @return true면 정답 신뢰 가능, false면 폐기
     */
    private boolean verifyAnswer(GeneratedQuizDto quiz, List<String> recentQuestions, String extraVerifyRules) {
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

        // 실험용 임시 검증 기준 (dry-run 워크벤치 전용, null 이면 프로덕션과 동일)
        String experimentalCriteria = "";
        if (extraVerifyRules != null && !extraVerifyRules.isBlank()) {
            experimentalCriteria = "\n추가 검증 기준 (아래도 반드시 적용):\n" + extraVerifyRules + "\n";
        }

        // 이력이 있을 때만 의미적 중복 판정 기준을 추가한다.
        String duplicationCriterion = "";
        String recentQuestionsSection = "";
        if (recentQuestions != null && !recentQuestions.isEmpty()) {
            duplicationCriterion = """
                    8. 아래 "최근 출제 문제 목록"의 어떤 문항과라도 표현만 다를 뿐
                       사실상 같은 개념·같은 인과를 묻는 중복 문제면 valid는 false입니다.
                    """;
            recentQuestionsSection = """

                    최근 출제 문제 목록:
                    %s
                    """.formatted(formatQuestionList(recentQuestions));
        }

        String verifyPrompt = """
                다음 경제 퀴즈가 객관식 문항으로서 유효한지 판단하세요.
                아래 "경제 인과 룰북"과 경제 지식, 그리고 제공된 정보만으로 판단하세요.

                경제 인과 룰북 (방향 판정의 절대 기준):
                %s

                문제: %s

                전체 보기:
                %s

                정답으로 표시된 보기: %s

                해설: %s

                핵심 용어(keyword): %s

                검증 기준:
                1. 전체 보기 중 경제학 교과서 기준으로 의미상 명확히 옳은 보기가 정확히 하나여야 합니다.
                   - 정답 외 보기가 경제학적으로 참인 '다른 효과'이거나, 정답과 같은 사안의
                     '반대쪽 절반'이면 정답이 둘 이상이 되므로 valid는 false입니다.
                     (예: 환율 상승의 여러 참인 효과가 여러 보기에 흩어져 있는 경우,
                      "전망이 엇갈리는 이유"에 양쪽 입장이 각각 보기로 있는 경우)
                2. 그 유일하게 옳은 보기가 정답으로 표시된 보기와 같아야 합니다.
                3. 정답의 인과 방향이 위 룰북과 일치해야 합니다. 룰북과 반대 방향이면 valid는 false입니다.
                   (예: "환율 하락→수출 증가", "환율 상승→수출 가격 상승·경쟁력 하락"은 룰북 위반)
                4. 해설이 정답을 올바르게 뒷받침해야 합니다. 해설이 정답과 모순되거나
                   다른 보기를 설명하면 valid는 false입니다.
                5. 핵심 용어(keyword)의 정의가 경제학적으로 정확해야 합니다.
                   (예: "환율 상승: 통화 가치가 높아지는 것"은 원/달러 기준 오정의이므로 false)
                6. 두 개 이상 옳거나, 정답이 틀렸거나, 불확실하면 valid는 false입니다.
                7. 문제는 문제 텍스트만으로 자립적으로 이해 가능해야 합니다. 특정 기사·시점의
                   사건을 알아야만 성립하는 전제가 깔려 있으면 valid는 false입니다.
                   (예: "전망이 엇갈리는 이유는?" — 엇갈린다는 사실 자체가 기사 지식.
                    "수요가 급증하는 이유는?" — 급증했다는 사실이 기사 지식.
                    단, 전제가 문제 문장 안에 서술돼 있으면 자립적이므로 통과)
                8. 정답 문장이 문제의 전제와 내적으로 모순되면 valid는 false입니다.
                   (예: 문제가 "동결 확률이 하락했을 때"를 전제하는데 정답이
                    "금리가 고정될 가능성이 커지면서 …"라고 서술 — 전제와 정답이 충돌)
                9. 정답은 질문이 실제로 묻는 것에 대한 답이어야 합니다. 질문이 '주기가
                   반복되는 원인'을 묻는데 정답이 한 방향의 현상만 설명하면 false입니다.
                10. 정답 외 보기 각각을 독립된 진술로 떼어 놓고 판정하세요. 기사 내용 또는
                    일반 경제 원리에 비추어 참인 오답이 하나라도 있으면 valid는 false입니다.
                    "주된/가장" 같은 수식어로 정답을 좁혀 참인 보기를 오답 처리하는 것도
                    허용되지 않습니다.
                11. 보기 중 문법이 깨졌거나 의미가 통하지 않는 비문이 있으면 valid는 false입니다.
                12. 정답이 질문에 직접 답하지 않거나(질문은 이유를 묻는데 정답이 이유가 아님),
                    동어반복 수준으로 모호하면 valid는 false입니다.
                13. 질문이 경제 주체의 일반적 반응·행동·대응을 예측하게 하면
                    (예: "수출 기업의 반응은?") valid는 false입니다. 행동 예측은 유일 정답이
                    성립하지 않습니다. 질문은 사실·인과·정의·비교·메커니즘 중 하나여야 합니다.
                %s%s%s
                위 기준을 모두 만족하면 {"valid": true},
                만족하지 않으면 {"valid": false, "reason": "이유"} 를 반환하세요.
                JSON만 반환하고 다른 텍스트는 금지입니다.
                """.formatted(CAUSAL_RULEBOOK, quiz.getQuestion(), choicesText, answerContent,
                        nullToEmpty(quiz.getExplanation()), nullToEmpty(quiz.getKeyword()),
                        duplicationCriterion, recentQuestionsSection, experimentalCriteria);

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

    /**
     * 시스템 프롬프트. 예시 블록은 카테고리별로 다르다.
     *
     * 운영 데이터 분석에서 프롬프트 개정 후 문항의 38.1%가 프롬프트 자체에서
     * 파생됐음이 확인됐다 (예시 변주 17.9% + 룰북 문장 재진술 22.6%).
     * 대응: ① 룰북에 "출제 소재 금지" 명시, ② 예시를 해당 카테고리 것만 노출,
     * ③ 예시 개념을 "이미 출제된 것"으로 간주시켜 복제 차단.
     * 테스트를 위해 package-private static.
     */
    static String systemPrompt(Category category) {
        return """
            당신은 경제·금융 개념 교육용 퀴즈 출제 전문가입니다.

            ## 역할
            뉴스 기사를 보고 ① 퀴즈 출제 적합 여부를 판단하고, ② 적합하면 사용자 메시지에
            지정된 카테고리에 정확히 맞는 경제 개념 퀴즈를 만듭니다. 부적합하면 SKIP.

            ## 핵심 경제 인과 룰북 (정답 판정의 절대 기준)
            아래 인과 방향과 충돌하는 보기는 절대 정답이 될 수 없습니다.

            """ + CAUSAL_RULEBOOK + """

            ※ 위 룰북은 보기의 옳고 그름을 '판정'하는 기준일 뿐, 출제 소재 목록이 아닙니다.
               룰북의 인과 문장을 그대로 문제·정답으로 재진술하는 출제는 금지합니다.
               (예: "환율 상승이 수출에 미치는 영향은?" 정답 "수출 증가" — 룰북 재진술이므로 금지)

            ## SKIP 기준 (하나라도 해당하면 SKIP)
            - 카테고리 불일치: 기사가 사용자 메시지의 지정 카테고리와 직접 관련 없음
              (예: 카테고리가 EXCHANGE_RATE인데 기사가 금리 얘기만 하면 SKIP.
               카테고리가 REAL_ESTATE인데 은행 예금금리 인상 기사면 SKIP.)
            - 특정 기업·브랜드·상품을 홍보하는 내용
            - 특정 인물의 발언·의견·전망을 묻는 내용
            - 특정 기업의 재무제표·회계 항목 세부(충당부채·특정 계정 처리 등)를 알아야
              풀 수 있는 내용 (일반 경제 개념이 아니라 그 기업 공시 지식이므로 SKIP)
            - 독자에게 생소할 수 있는 전문 고유명사·약어가 설명 없이 핵심으로 사용됨
            - 사건의 단순 수치(인원수, 날짜, 금액)를 묻는 내용
            - 사설·칼럼 등 필자의 주관적 분석이 주를 이루는 기사
            - 특정 기관·기업의 인사·임명·조직 개편이 주된 내용인 기사
              (예: "○○○, 연준 태스크포스 의장 임명" — 시사 고유명사 지식이지 경제 개념이 아님)

            ## 금지 출제 패턴 (이전 출제에서 과다 반복되어 학습 가치가 낮음)
            아래 패턴 또는 동치 표현은 절대 사용하지 마세요:
            - "미국 국채 금리 상승 → 주식 시장 매력/투자 매력 감소" 류
            - "변동금리 대출 + 금리 인상 → 이자 부담 증가" 류 (동어반복)
            - "주담대 증가 → 가계부채 증가" 류
            - "공포지수/변동성 지수 상승 → 투자자 매도/위험 회피" 류
            - "X가 상승/인상되면 Y는?" 형식의 단순 1차원 직선 인과
            - 경제 주체(기업/투자자/가계/정부)가 "일반적으로 어떻게 반응·행동·대응하는가"를
              묻는 행동 예측형 질문 (예: "수출 기업의 반응은?") — 행동은 상황·주체마다 달라
              유일 정답이 성립하지 않음. 질문은 사실·인과·정의·비교·메커니즘 중 하나를 물을 것
            - "의견·전망이 엇갈리는/찬반이 갈리는 이유는?" 처럼 논쟁적 사안을 묻고
              한쪽 입장만 정답으로 두는 문제 — 반대 입장 보기도 참이므로 정답이 둘이 됨
              (예: "연준 전망이 엇갈리는 이유?" 정답 "인플레 우려로 인상 주장" —
               '경기 둔화로 동결 주장' 보기도 똑같이 옳아 복수 정답이 됨)
            대신 메커니즘(왜 그렇게 되는지), 정의(개념 자체), 비교(두 개념 차이),
            응용(현실 사례·산업별 차별 영향)을 묻는 문제를 만드세요.

            ## 문제 자립성 규칙 (사용자는 기사를 보지 못합니다)
            퀴즈 화면에는 기사가 함께 표시되지 않습니다. 문제는 문제 텍스트만 읽어도
            완전히 이해되고 풀 수 있어야 합니다.
            - 기사 속 사건·상황을 아는 것처럼 전제로 깔지 마세요.
              ("전망이 엇갈리는 이유는?" — 엇갈린다는 사실을 사용자는 모릅니다.
               "수요가 급증하는 이유는?" — 급증했다는 사실이 기사 지식입니다.)
            - 전제가 필요하면 문제 문장 안에 직접 서술하세요.
              (나쁨: "연준의 금리 전망이 엇갈리는 이유는?"
               좋음: "중앙은행 내부에서 물가 안정을 중시하는 쪽과 경기 부양을 중시하는
                     쪽의 금리 전망이 갈리곤 한다. 이런 견해차를 만드는 근본 요인은?")

            ## 보기 상호배타성 규칙 (정답이 정확히 하나가 되도록)
            4개 보기는 서로 배타적이어야 하며, 정답 외 3개는 '명확히 틀린' 내용이어야 합니다.
            - 정답과 오답이 같은 사안의 '서로 다른 절반'이면 안 됩니다 (둘 다 참 → 복수 정답).
            - 오답이 경제학적으로 참인 다른 효과이면 안 됩니다
              (예: 환율 상승 문제에서 '원자재 수입비 증가'와 '원화환산 수익 증가'가
               보기에 함께 있으면 둘 다 참이라 정답이 둘).

            ## 좋은 문제 예시 (형식 참고용 — 소재 복제 금지)
            아래 예시는 문제 '형식'(메커니즘·정의·비교·응용)의 참고용입니다.
            예시의 개념은 '이미 출제된 것'으로 간주하세요. 예시의 소재·개념·정답 문장을
            복제하거나 변주하지 말고, 기사에서 찾은 새로운 개념에 형식만 적용하세요.

            """ + categoryExamples(category) + """


            ## 나쁜 문제 예시 (절대 이렇게 출제하지 마세요)
            - Q. 미국 국채 금리가 상승하면 주식시장에는 어떤 영향? (1차원 인과)
            - Q. 금리가 인상되면 대출 수요는? (동어반복)
            - Q. 변동금리 대출에서 금리가 오르면 이자 부담은? (동어반복)

            ## 보기 작성 규칙 (위반 시 시스템이 자동 폐기하므로 반드시 준수)
            - 4개 보기의 길이를 비슷하게 맞추세요. 정답이 가장 길고 상세한 보기가 되면
              문제를 읽지 않고 길이만으로 정답을 찍을 수 있게 됩니다.
            - 오답에 절대 표현(항상, 반드시, 무조건, 절대, 전혀)을 쓰지 마세요.
            - "변화가 없다", "영향을 미치지 않는다", "무관하다" 류의 오답을 만들지 마세요.
              오답은 학습자가 실제로 헷갈릴 만한 그럴듯한 오개념이어야 합니다.
            - 완곡 표현("~할 수 있다", "~하는 경향이 있다")을 정답에만 쓰지 마세요.
              4개 보기의 문체와 단정 수준을 통일하세요.
            - 모든 보기는 그 자체로 완결된 자연스러운 한국어 문장이어야 합니다.
              문법이 어색하거나 의미가 통하지 않는 비문 오답은 절대 금지합니다.
              오답도 현실에서 누군가 그럴듯하다고 믿을 만한 진술이어야 합니다.
            - 오답 각각에 대해 자문하세요: "이 진술만 따로 떼어 놓고 보면, 경제 상식이나
              기사 내용에 비추어 참인가?" 참이면 폐기하고 명확히 거짓인 진술로 교체하세요.
              일반 경제 원리로 참인 진술(예: 자금 유입은 통화 강세 요인)을 오답으로
              쓰는 것을 금지합니다. "주된/가장" 수식어로 정답을 좁혀 참인 보기를
              오답 처리하는 구조도 금지합니다.

            ## 정답 자가 점검 (출제 직후 반드시 수행)
            1. 정답이 위 "핵심 경제 인과 룰북"과 일치하는가?
            2. 정답 외 3개 보기 중 옳다고 볼 여지가 있는 보기가 있는가? 있으면 폐기.
            3. 문제가 "금지 출제 패턴"에 해당하거나, 룰북·예시 문장의 재진술·변주인가? 해당하면 폐기.
            4. 문제가 지정된 카테고리와 정확히 일치하는가? 아니면 SKIP.
            5. question·choices·explanation·keyword 가 모두 100% 한국어로 작성되었는가?
               영어 문장이나 영어 단어가 3개 이상 연속 등장하면 폐기.
               (단, KOSPI·GDP·ETF·OECD 같이 한국에서 통상 영문으로 쓰는 약어는 단일 토큰으로만 허용)
            6. "보기 작성 규칙"을 지켰는가? (보기 길이 균형, 오답에 절대 표현·무변화 표현 없음,
               문체 통일) 아니면 보기를 다시 작성.
            7. 기사를 전혀 못 본 사람이 문제 텍스트만 읽어도 완전히 이해되는가?
               기사 속 사건을 전제로 깔았다면 전제를 문제 문장에 직접 서술하거나 재출제.
            8. 정답이 질문이 묻는 것에 직접 답하는가? 질문이 이유를 물으면 정답은
               구체적 이유여야 합니다. 동어반복이나 모호한 일반론 정답은 폐기.
            하나라도 불확실하면 SKIP 또는 재출제.

            ## 응답 형식 (반드시 JSON만, 다른 텍스트 절대 금지)

            SKIP인 경우:
            {"skip": true, "skipReason": "사유"}

            퀴즈 생성인 경우 (모든 한국어 필드는 100% 한국어로 작성. 영어 문장 혼용 금지):
            ※ 정답 위치는 orderNum=2로 고정하지 말고, 문제마다 1~4 중 하나로 고르게 분산하세요.
               아래 true 위치는 JSON 형식 예시일 뿐이며 그대로 따라 하면 안 됩니다.
            {
              "skip": false,
              "question": "퀴즈 문제 (개념·메커니즘·비교·응용 중 하나, 100% 한국어)",
              "choices": [
                {"orderNum": 1, "content": "보기1 (100% 한국어)", "isAnswer": false},
                {"orderNum": 2, "content": "보기2 (100% 한국어)", "isAnswer": false},
                {"orderNum": 3, "content": "보기3 (100% 한국어)", "isAnswer": true},
                {"orderNum": 4, "content": "보기4 (100% 한국어)", "isAnswer": false}
              ],
              "explanation": "정답 해설 (2~3문장, 100% 한국어)",
              "keyword": "핵심 경제 용어: 한 줄 개념 설명 (100% 한국어. KOSPI·GDP·ETF 같은 정착 영문 약어만 예외)"
            }
            """;
    }

    /**
     * 카테고리별 좋은 문제 예시 2개.
     *
     * 카테고리를 분리한 이유: 예시 4개를 전 카테고리에 공통 노출했더니 운영 데이터에서
     * 개정 후 문항의 17.9%가 예시의 변주였고(정답 문장까지 복제 8.3%), 같은 날 두
     * 카테고리가 같은 예시에 앵커되어 사실상 동일 문항을 이중 출제한 사례도 확인됐다.
     * 과다 복제된 예시(기준금리→예금금리 8회, 기준금리→아파트 매매가 6회)는 새 소재로 교체.
     */
    static String categoryExamples(Category category) {
        return switch (category) {
            case INTEREST_RATE -> """
                예시 1 — 개념 비교:
                Q. 콜금리와 기준금리의 가장 큰 차이는?
                정답: 콜금리는 은행 간 초단기 대차 시장에서 형성되는 실제 금리이고,
                      기준금리는 한국은행이 정책적으로 결정해 발표하는 금리

                예시 2 — 메커니즘:
                Q. 시장금리가 오르면 이미 발행된 채권의 가격이 떨어지는 이유는?
                정답: 새로 발행되는 채권의 이자율이 더 높아져, 이자율이 낮은 기존 채권은
                      가격을 낮춰야 매수자를 찾을 수 있기 때문""";
            case EXCHANGE_RATE -> """
                예시 1 — 응용·차별 영향:
                Q. 환율이 가파르게 상승하는 시기에 가장 타격을 받는 기업 유형은?
                정답: 원자재·부품을 해외에서 수입해 국내에서 판매하는 내수 기반 제조업체

                예시 2 — 개념 비교:
                Q. 명목환율과 실질환율의 차이는?
                정답: 명목환율은 두 통화의 교환 비율 그 자체이고, 실질환율은 양국의
                      물가 수준을 반영해 실제 구매력 기준으로 조정한 환율""";
            case STOCK -> """
                예시 1 — 메커니즘:
                Q. 금리 인상기에 성장주가 가치주보다 더 크게 하락하는 경향이 있는 이유는?
                정답: 성장주의 가치는 먼 미래의 이익에 크게 의존하는데, 할인율이 오르면
                      미래 이익의 현재 가치가 상대적으로 더 크게 줄어들기 때문

                예시 2 — 정의:
                Q. 어떤 기업의 PER이 업종 평균보다 높다는 것은 무엇을 의미하는가?
                정답: 주가가 주당순이익 대비 높게 형성되어 있다는 뜻으로, 시장이 그 기업의
                      이익 성장 기대를 크게 반영하고 있다는 의미""";
            case REAL_ESTATE -> """
                예시 1 — 개념 비교:
                Q. 주택담보대출 규제에서 LTV와 DSR의 가장 큰 차이는?
                정답: LTV는 담보 주택의 가치 대비 대출 한도를 제한하고,
                      DSR은 대출자의 소득 대비 전체 부채 원리금 상환 부담을 제한한다

                예시 2 — 메커니즘:
                Q. 전세가격 상승이 주택 매매가격을 밀어 올리는 주된 경로는?
                정답: 전세가가 매매가에 가까워질수록 전세 수요의 일부가 매매 수요로
                      전환되어 매수세가 늘어나기 때문""";
        };
    }

    private String userPrompt(String title, String content, Category category, List<String> recentQuestions) {
        // 최근 출제 이력이 있을 때만 중복 금지 섹션을 추가한다.
        String recentSection = "";
        if (recentQuestions != null && !recentQuestions.isEmpty()) {
            recentSection = """

                [최근 이미 출제된 문제 — 중복 출제 금지]
                %s
                ※ 표현이 다르더라도 위 목록과 사실상 같은 개념·같은 인과를 묻는 문제는 중복입니다.
                ※ 이 기사에서 위 목록에 없는 '새로운' 개념을 찾아 출제하세요.
                   기사에서 새로운 개념을 찾을 수 없으면 SKIP하세요.
                """.formatted(formatQuestionList(recentQuestions));
        }

        return """
            다음 뉴스 기사를 검토하고, SKIP 여부를 먼저 판단한 뒤 퀴즈를 출제하거나 SKIP을 반환하세요.

            [퀴즈 카테고리]: %s (%s)
            ※ 이 기사로 만들 퀴즈는 반드시 위 카테고리와 직접 관련된 경제 개념을 다뤄야 합니다.
            ※ 기사가 카테고리와 무관하면(예: EXCHANGE_RATE 카테고리인데 기사가 금리 얘기만 함,
              REAL_ESTATE 카테고리인데 은행 예금 기사) 반드시 SKIP하세요.

            뉴스 제목: %s
            뉴스 내용: %s
            %s
            출제 시 주의사항:
            - 시스템 프롬프트의 "금지 출제 패턴"을 절대 따라하지 마세요
            - 시스템 프롬프트의 "좋은 문제 예시"처럼 메커니즘·정의·비교·응용 중 하나로 출제하세요
            - 문제는 뉴스의 특정 사실(숫자·인명·고유명사)이 아닌 경제 '개념'을 묻으세요
            - 오답 보기는 그럴듯하지만 명확히 틀린 내용이어야 합니다
            - 한국어로 작성하세요
            - 경제 변수를 사용할 때는 반드시 방향을 명시하세요 ("환율 상승" O, "환율 변동" X)
            """.formatted(category.name(), category.getDisplayName(), title, content, recentSection);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 문항 목록을 프롬프트용 "- 문항" 줄 목록으로 변환. */
    private String formatQuestionList(List<String> questions) {
        return questions.stream()
                .map(q -> "- " + q)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
