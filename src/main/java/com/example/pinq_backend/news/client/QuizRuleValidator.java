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
     * 퀴즈의 문제와 정답이 룰북의 인과와 일치하는지 검사한다.
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

        // 2) 언어 일관성 검사 — 한국어 필드에 3+ 영어 단어 연속 등장 시 폐기
        Result langResult = checkKoreanOnly(quiz);
        if (!langResult.valid()) return langResult;

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
