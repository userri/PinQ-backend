package com.example.pinq_backend.news.client;

import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 명백한 경제 인과 위반을 차단하는 룰베이스 퀴즈 검증기.
 *
 * 검증 흐름에서의 위치: GPT 생성 → [여기] → Claude cross-model 검증.
 *
 * 왜 룰베이스인가:
 *  - 환율↑+수입↑, 금리↑+대출 수요↑ 같은 인과는 결정적이라 if문 몇 줄로 100% 잡힘
 *  - API 호출 0원, 1ms 미만, 같은 입력 같은 결과
 *  - Claude는 같은 영역에서 가끔 통과시키는 미묘한 케이스가 있음 (LLM 한계)
 *  - Claude 호출 전 단계에 두어 API 비용 절감
 *
 * 매칭 방식:
 *  - 문제: questionTokensAll 키워드가 모두 포함되어야 매치 (AND 의미)
 *  - 정답: forbiddenAnswerPhrases 구절 중 하나라도 정답에 포함되면 위반 (OR 의미)
 *  - 한 룰이 위반되면 즉시 폐기
 *
 * 토큰이 아닌 '구절' 단위로 정답을 검사하는 이유:
 *  복합 정답("수출이 증가하고 수입이 감소한다")에서 단순 토큰 매칭은
 *  '수입' + '증가'를 동시 발견해 오탐(false positive)을 일으킨다.
 *  구절 단위("수입이 증가", "수입 증가")로 보면 이런 케이스를 피할 수 있다.
 *
 * 룰북에 없는 미묘한 케이스는 다음 단계인 Claude 검증이 잡는다.
 */
@Component
public class QuizRuleValidator {

    /**
     * 검증 결과.
     *
     * @param valid  true면 통과, false면 폐기 대상
     * @param reason 폐기 사유 (valid=true면 null)
     */
    public record Result(boolean valid, String reason) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * 영어 단어 3개 이상이 공백으로 연속해서 등장하는 패턴.
     *
     * 예시 매치: "project's profit recovery" (3단어 연속)
     * 비매치: "KOSPI", "GDP는", "ETF에 투자" (단일 영문 토큰은 통과)
     *
     * 출제 결과에 "재건축 project's profit recovery system" 같이 한국어와 영어 구절이
     * 섞이는 케이스를 차단하기 위한 것. cross-model 검증은 경제학적 진위만 보기 때문에
     * 언어 일관성은 여기서 잡는다.
     */
    private static final Pattern ENGLISH_WORD_RUN =
            Pattern.compile("[a-zA-Z][a-zA-Z']*(?:\\s+[a-zA-Z][a-zA-Z']*){2,}");

    private record CausalRule(
            List<String> questionTokensAll,
            List<String> forbiddenAnswerPhrases,
            String description
    ) {}

    private static final List<CausalRule> RULES = List.of(
            // ── [환율] 상승 → 수입은 감소, 수출은 증가 ────────────────────────────
            new CausalRule(
                    List.of("환율", "상승"),
                    List.of("수입이 증가", "수입은 증가", "수입 증가",
                            "수입이 늘", "수입은 늘", "수입이 많아"),
                    "환율 상승 시 수입은 감소해야 함"
            ),
            new CausalRule(
                    List.of("환율", "상승"),
                    List.of("수출이 감소", "수출은 감소", "수출 감소",
                            "수출이 줄", "수출은 줄"),
                    "환율 상승 시 수출은 증가해야 함"
            ),
            // ── [환율] 하락 → 수입은 증가, 수출은 감소 ────────────────────────────
            new CausalRule(
                    List.of("환율", "하락"),
                    List.of("수입이 감소", "수입은 감소", "수입 감소",
                            "수입이 줄", "수입은 줄"),
                    "환율 하락 시 수입은 증가해야 함"
            ),
            new CausalRule(
                    List.of("환율", "하락"),
                    List.of("수출이 증가", "수출은 증가", "수출 증가",
                            "수출이 늘", "수출은 늘"),
                    "환율 하락 시 수출은 감소해야 함"
            ),
            // ── [금리] 인상/상승 → 대출 수요 감소 ─────────────────────────────────
            new CausalRule(
                    List.of("금리", "인상"),
                    List.of("대출 수요가 증가", "대출 수요는 증가", "대출 수요 증가",
                            "대출 수요가 늘", "대출 수요가 커"),
                    "금리 인상 시 대출 수요는 감소"
            ),
            new CausalRule(
                    List.of("금리", "상승"),
                    List.of("대출 수요가 증가", "대출 수요는 증가", "대출 수요 증가",
                            "대출 수요가 늘", "대출 수요가 커"),
                    "금리 상승 시 대출 수요는 감소"
            ),
            // ── [금리] 인하 → 대출 수요 증가 ─────────────────────────────────────
            new CausalRule(
                    List.of("금리", "인하"),
                    List.of("대출 수요가 감소", "대출 수요는 감소", "대출 수요 감소",
                            "대출 수요가 줄"),
                    "금리 인하 시 대출 수요는 증가"
            ),
            // ── [금리] 인상 → 채권 가격 하락 (역상관) ──────────────────────────────
            new CausalRule(
                    List.of("금리", "인상"),
                    List.of("채권 가격이 상승", "채권 가격은 상승", "채권 가격 상승",
                            "채권 가격이 오르", "채권 가격이 올라"),
                    "금리 인상 시 채권 가격은 하락 (역상관)"
            ),
            new CausalRule(
                    List.of("금리", "상승"),
                    List.of("채권 가격이 상승", "채권 가격은 상승", "채권 가격 상승",
                            "채권 가격이 오르", "채권 가격이 올라"),
                    "금리 상승 시 채권 가격은 하락 (역상관)"
            ),
            // ── [금리] 인하 → 채권 가격 상승 ─────────────────────────────────────
            new CausalRule(
                    List.of("금리", "인하"),
                    List.of("채권 가격이 하락", "채권 가격은 하락", "채권 가격 하락",
                            "채권 가격이 떨어"),
                    "금리 인하 시 채권 가격은 상승"
            ),
            // ── [금리] 인상 + 변동금리 → 이자 부담 증가 ────────────────────────────
            new CausalRule(
                    List.of("금리", "인상", "변동금리"),
                    List.of("이자 부담이 감소", "이자 부담은 감소", "이자 부담 감소",
                            "이자 부담이 줄"),
                    "금리 인상 + 변동금리 시 이자 부담 증가"
            ),
            new CausalRule(
                    List.of("금리", "상승", "변동금리"),
                    List.of("이자 부담이 감소", "이자 부담은 감소", "이자 부담 감소",
                            "이자 부담이 줄"),
                    "금리 상승 + 변동금리 시 이자 부담 증가"
            )
    );

    /**
     * 퀴즈의 문제와 정답이 룰북의 인과와 일치하는지, 보기 구성이
     * 정답을 유출하지 않는지 검사한다.
     *
     * @return ok() 또는 fail(사유)
     */
    public Result validate(GeneratedQuizDto quiz) {
        if (quiz == null || quiz.getQuestion() == null || quiz.getChoices() == null) {
            return Result.ok();
        }

        Optional<String> answerOpt = quiz.getChoices().stream()
                .filter(GeneratedQuizDto.ChoiceDto::isAnswer)
                .map(GeneratedQuizDto.ChoiceDto::getContent)
                .findFirst();
        if (answerOpt.isEmpty()) return Result.ok();

        String question = quiz.getQuestion();
        String answer = answerOpt.get();

        // 1) 경제 인과 위반 검사
        for (CausalRule rule : RULES) {
            if (matchesAll(question, rule.questionTokensAll())
                    && containsAny(answer, rule.forbiddenAnswerPhrases())) {
                return Result.fail(rule.description());
            }
        }

        // 2) 문항 형식 검사 — 4지선다인데 서술형/명령형 지시문이면 폐기
        if (COMMAND_FORM_ENDING.matcher(question.trim()).find()) {
            return Result.fail("객관식인데 서술형·명령형 지시문 (예: '기술하시오'): " + question);
        }

        // 3) 오답 품질 검사 — 메타 전략(길이·문체·무변화)으로 정답이 유출되는 퀴즈 차단
        Result distractorResult = checkDistractorQuality(quiz);
        if (!distractorResult.valid()) return distractorResult;

        // 4) 언어 일관성 검사 — 한국어 필드에 3+ 영어 단어 연속 등장 시 폐기
        Result langResult = checkKoreanOnly(quiz);
        if (!langResult.valid()) return langResult;

        // 5) keyword 형식 검사 — "용어: 정의" 형식이어야 하는데 단어 나열이면 폐기.
        //    운영 재발 사례(id 289, 318 등): "수리비, 소비자물가지수, 부품가격 상승, ..."
        //    콜론 없이 콤마 2개 이상이면 정의 없는 나열형으로 판정한다.
        String keyword = quiz.getKeyword();
        if (keyword != null && !keyword.contains(":") && !keyword.contains("：")
                && keyword.chars().filter(ch -> ch == ',').count() >= 2) {
            return Result.fail("keyword 나열형 (\"용어: 정의\" 형식 위반): " + keyword);
        }

        return Result.ok();
    }

    /**
     * 객관식 문항에 부적합한 서술형·명령형 지시문 어미.
     *
     * 운영 사고(2026-07-09 id 189 "…영향을 기술하시오."): 4지선다인데 문제가
     * 서술을 요구하는 명령형으로 끝나면 문항 형식이 어긋난다. 의문문("…무엇인가?",
     * "…어떻게 되는가?")으로 물어야 한다. 정상적인 의문형 어미('-는가', '-인가',
     * '-까')는 매치하지 않고, 명시적 서술 지시 동사만 잡는다.
     */
    private static final Pattern COMMAND_FORM_ENDING = Pattern.compile(
            "(설명|기술|서술|논|약술|비교)하(시오|라|여라)\\.?$"
                    + "|(쓰|고르|구하|말하)(시오|라)\\.?$");

    // ── 오답 품질 룰 (정답 유출 패턴 차단) ────────────────────────────────
    //
    // 운영 데이터(172문항) 분석 근거:
    //  - 절대 표현·무변화 표현은 오답에만 등장 (정답 0건) → 존재 자체가 "이건 오답" 신호
    //  - 최장 보기=정답 비율 68% (기대치 25%) → "가장 긴 보기 찍기"만으로 50.6% 정답
    //  - 완곡 표현은 정답의 18.6% vs 오답의 5.2% → 문체만으로 정답 유추 가능
    // 시스템 프롬프트의 '보기 작성 규칙'이 1차 방어이고, 여기는 위반 시 강제 폐기선.

    /** 오답에서만 발견되어 온 절대 표현. "절대적"(수식어)은 정상 표현이라 제외. */
    private static final Pattern ABSOLUTE_EXPRESSION =
            Pattern.compile("항상|반드시|무조건|절대(?!적)|전혀");

    /** '변화 없음' 류 표현 — 학습 가치가 없는 게으른 오답이자 오답 확정 신호. */
    private static final Pattern NO_CHANGE_EXPRESSION = Pattern.compile(
            "변화[가는]?\\s*없|변동[이은]?\\s*없|영향[이을은]?\\s*(?:없|미치지 않|주지 않)"
                    + "|무관하|관계가 없|관련이 없|아무런 영향|동일하게 유지|일정하게 유지|그대로 유지");

    /** 완곡(헤지) 표현 — 정답에만 있으면 문체 단서가 된다. "~할/늘어날/커질 수 있다" 활용형 포괄. */
    private static final Pattern HEDGE_EXPRESSION = Pattern.compile("수 있|가능성|경향");

    /**
     * 정답이 최장 보기이면서 오답 평균 길이의 이 배수 이상이면 길이 편향으로 폐기.
     *
     * 1.5 → 1.3 하향(2026-07-11): gpt-4.1-mini 표본 8문항에서 최장=정답 5건의
     * 비율이 1.19~1.48로 전부 1.5 직하에 분포 — 기존 문턱이 실질 무력화 상태였다.
     * 1.3이면 5건 중 4건을 차단하면서 오답과 비슷한 길이의 정상 문항은 통과한다.
     */
    private static final double ANSWER_LENGTH_RATIO_LIMIT = 1.3;

    private Result checkDistractorQuality(GeneratedQuizDto quiz) {
        String answer = null;
        List<String> distractors = new java.util.ArrayList<>();
        for (GeneratedQuizDto.ChoiceDto choice : quiz.getChoices()) {
            String content = choice.getContent() == null ? "" : choice.getContent();
            if (choice.isAnswer()) {
                answer = content;
            } else {
                distractors.add(content);
            }
        }
        if (answer == null || distractors.isEmpty()) return Result.ok();

        // 절대어·무변화 오답: 존재만으로 소거법이 가능해진다
        for (String distractor : distractors) {
            if (ABSOLUTE_EXPRESSION.matcher(distractor).find()) {
                return Result.fail("오답에 절대 표현 (정답 유출 단서): " + distractor);
            }
            if (NO_CHANGE_EXPRESSION.matcher(distractor).find()) {
                return Result.fail("무변화 류 오답 (정답 유출 단서): " + distractor);
            }
        }

        // 길이 편향: 정답이 유일하게 가장 길면서 오답 평균의 1.5배 이상
        int answerLength = answer.length();
        double avgDistractorLength = distractors.stream()
                .mapToInt(String::length).average().orElse(0);
        boolean answerIsStrictlyLongest = distractors.stream()
                .allMatch(d -> d.length() < answerLength);
        if (answerIsStrictlyLongest && answerLength >= avgDistractorLength * ANSWER_LENGTH_RATIO_LIMIT) {
            return Result.fail("정답 길이 편향: 정답 %d자가 오답 평균 %.1f자의 %.1f배"
                    .formatted(answerLength, avgDistractorLength, answerLength / avgDistractorLength));
        }

        // 헤지 비대칭: 정답에만 완곡 표현이 있으면 문체로 정답이 드러난다
        boolean answerHedged = HEDGE_EXPRESSION.matcher(answer).find();
        boolean anyDistractorHedged = distractors.stream()
                .anyMatch(d -> HEDGE_EXPRESSION.matcher(d).find());
        if (answerHedged && !anyDistractorHedged) {
            return Result.fail("정답에만 완곡 표현 (문체 단서): " + answer);
        }

        return Result.ok();
    }

    /**
     * question·choices·explanation·keyword 필드에 3개 이상 연속된 영어 단어가
     * 등장하면 폐기. 단일 영문 약어(KOSPI, GDP 등)는 통과.
     */
    private Result checkKoreanOnly(GeneratedQuizDto quiz) {
        if (hasEnglishRun(quiz.getQuestion())) {
            return Result.fail("question에 영어 구절 혼용");
        }
        for (GeneratedQuizDto.ChoiceDto choice : quiz.getChoices()) {
            if (hasEnglishRun(choice.getContent())) {
                return Result.fail("choice에 영어 구절 혼용: " + choice.getContent());
            }
        }
        if (hasEnglishRun(quiz.getExplanation())) {
            return Result.fail("explanation에 영어 구절 혼용");
        }
        if (hasEnglishRun(quiz.getKeyword())) {
            return Result.fail("keyword에 영어 구절 혼용");
        }
        return Result.ok();
    }

    private boolean hasEnglishRun(String text) {
        if (text == null || text.isBlank()) return false;
        return ENGLISH_WORD_RUN.matcher(text).find();
    }

    private boolean matchesAll(String text, List<String> tokens) {
        for (String t : tokens) {
            if (!text.contains(t)) return false;
        }
        return true;
    }

    private boolean containsAny(String text, List<String> phrases) {
        for (String p : phrases) {
            if (text.contains(p)) return true;
        }
        return false;
    }
}
