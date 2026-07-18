package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오답 품질 룰 검증. 실패 케이스들은 운영 데이터에서 확인된
 * 실제 정답 유출 패턴(절대어·무변화 오답, 길이 편향, 헤지 비대칭)이다.
 */
class QuizRuleValidatorTest {

    private final QuizRuleValidator validator = new QuizRuleValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("오답에 절대 표현이 있으면 폐기한다 (운영에서 절대어는 오답에만 등장, 정답 0건)")
    void distractorWithAbsoluteExpression_fails() throws Exception {
        // 실제 사례: id 27 "주식 시장은 항상 안정적으로 상승한다"
        GeneratedQuizDto quiz = quiz(
                "코스피 변동성 확대가 투자 전략에 미치는 영향은?",
                "분산 투자의 중요성이 커진다",
                List.of("주식 시장은 항상 안정적으로 상승한다",
                        "채권 비중을 늘릴 이유가 사라진다",
                        "단기 매매가 유리해진다"));

        QuizRuleValidator.Result result = validator.validate(quiz);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("절대 표현");
    }

    @Test
    @DisplayName("'절대적' 같은 정상 수식어는 절대 표현으로 오탐하지 않는다")
    void absoluteAsModifier_passes() throws Exception {
        GeneratedQuizDto quiz = quiz(
                "기준금리와 시장금리의 관계는?",
                "기준금리는 시장금리의 방향에 영향을 준다",
                List.of("시장금리는 절대적 기준에 의해 고정된다",
                        "두 금리는 별개의 시장에서 결정된다",
                        "시장금리가 기준금리를 결정한다"));

        assertThat(validator.validate(quiz).valid()).isTrue();
    }

    @Test
    @DisplayName("무변화 류 오답이 있으면 폐기한다 (운영에서 71건 전부 오답)")
    void noChangeDistractor_fails() throws Exception {
        // 실제 사례: id 70 오답 "영향을 미치지 않는다"
        GeneratedQuizDto quiz = quiz(
                "국채 발행 확대가 채권 시장에 미치는 영향은?",
                "공급 증가로 채권 가격에 하방 압력이 생긴다",
                List.of("채권 시장에는 아무런 영향을 미치지 않는다",
                        "채권 공급이 줄어든다",
                        "국채 수요가 사라진다"));

        QuizRuleValidator.Result result = validator.validate(quiz);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("무변화");
    }

    @Test
    @DisplayName("정답이 유일 최장이면서 오답 평균의 1.5배 이상이면 폐기한다")
    void answerLengthBias_fails() throws Exception {
        // 실제 사례 축약: id 155 — 정답 21자 vs 오답 평균 10자 (2.1배)
        GeneratedQuizDto quiz = quiz(
                "주택담보대출이 감소하는 상황에서 부동산 시장에 미치는 주요 영향은?",
                "매수 수요 감소로 인한 가격 하락 압력이 커진다",
                List.of("부동산 가격 안정",
                        "주택 거래량 급증",
                        "금융회사 수익 증가"));

        QuizRuleValidator.Result result = validator.validate(quiz);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("길이 편향");
    }

    @Test
    @DisplayName("정답이 최장이어도 1.5배 미만이면 통과한다 (보통 수준의 길이 차이는 허용)")
    void answerSlightlyLongest_passes() throws Exception {
        GeneratedQuizDto quiz = quiz(
                "기준금리 인하가 예금자에게 미치는 영향은?",
                "예금 이자 수익이 줄어들게 된다",     // 최장이지만 1.5배 미만
                List.of("예금 이자 수익이 늘어난다",
                        "예금 원금이 줄어들게 된다",
                        "예금 상품이 전부 사라진다"));

        assertThat(validator.validate(quiz).valid()).isTrue();
    }

    @Test
    @DisplayName("정답에만 완곡 표현이 있으면 폐기한다 (문체 단서)")
    void hedgeOnlyInAnswer_fails() throws Exception {
        GeneratedQuizDto quiz = quiz(
                "환율 변동성 확대가 기업 경영에 미치는 영향은?",
                "환헤지 비용이 늘어날 수 있다",
                List.of("환헤지 수요가 사라진다",
                        "수출 계약이 전부 취소된다",
                        "원자재 수입이 중단된다"));

        QuizRuleValidator.Result result = validator.validate(quiz);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("완곡 표현");
    }

    @Test
    @DisplayName("완곡 표현이 정답과 오답에 함께 있으면 통과한다 (문체 통일)")
    void hedgeInBothAnswerAndDistractor_passes() throws Exception {
        GeneratedQuizDto quiz = quiz(
                "환율 변동성 확대가 기업 경영에 미치는 영향은?",
                "환헤지 비용이 늘어날 수 있다",
                List.of("수출 단가 협상력이 커질 수 있다",
                        "원자재 조달처가 국내로 바뀐다",
                        "외화 부채 부담이 사라진다"));

        assertThat(validator.validate(quiz).valid()).isTrue();
    }

    @Test
    @DisplayName("객관식인데 서술형·명령형 지시문('기술하시오')이면 폐기한다")
    void narrativeCommandForm_fails() throws Exception {
        // 실제 사례: id 189 "…영향을 기술하시오."
        GeneratedQuizDto quiz = quiz(
                "환율 상승이 수출 기업의 영업이익에 미치는 영향을 기술하시오.",
                "원화 환산 수익이 늘어난다",
                List.of("수입 원자재 가격이 내린다",
                        "외국인 투자가 자동으로 늘어난다",
                        "해외 판매 가격이 반드시 오른다"));

        QuizRuleValidator.Result result = validator.validate(quiz);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("서술형");
    }

    @Test
    @DisplayName("정상적인 의문형 어미('무엇인가?')는 통과한다")
    void interrogativeForm_passes() throws Exception {
        // 보기 길이를 비슷하게 맞춰 형식 검사만 통과 여부를 본다
        GeneratedQuizDto quiz = quiz(
                "콜금리와 기준금리의 가장 큰 차이는 무엇인가요?",
                "콜금리는 시장에서 형성되는 실제 금리이다",
                List.of("둘은 사실상 완전히 동일한 금리이다",
                        "콜금리는 장기, 기준금리는 단기 금리이다",
                        "기준금리는 은행 간 거래에만 쓰이는 금리이다"));

        assertThat(validator.validate(quiz).valid()).isTrue();
    }

    @Test
    @DisplayName("기존 인과 룰도 계속 동작한다 (환율 상승 + 수입 증가 정답 차단)")
    void causalRule_stillWorks() throws Exception {
        GeneratedQuizDto quiz = quiz(
                "환율이 상승하면 수입에는 어떤 변화가 나타나는가?",
                "수입이 증가하게 된다",
                List.of("수입 물가가 오르게 된다",
                        "수입 결제 부담이 커지게 된다",
                        "수입 채산성이 나빠지게 된다"));

        QuizRuleValidator.Result result = validator.validate(quiz);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("수입은 감소");
    }

    /** 정답 1개 + 오답 3개로 4지선다 DTO 구성. */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("keyword가 콜론 없는 나열형이면 폐기한다")
    void listFormKeyword_isRejected() throws Exception {
        GeneratedQuizDto q = quizWithKeyword("수리비, 소비자물가지수, 부품가격 상승, 물가 상승 메커니즘");
        QuizRuleValidator.Result result = validator.validate(q);
        org.assertj.core.api.Assertions.assertThat(result.valid()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.reason()).contains("나열형");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("콜론이 있으면 정의 안에 콤마가 여러 개여도 통과한다")
    void definitionKeywordWithCommas_passes() throws Exception {
        GeneratedQuizDto q = quizWithKeyword("통화량(M2): 현금, 예금, 수시입출금 등을 포함하는 광의통화 지표");
        org.assertj.core.api.Assertions.assertThat(validator.validate(q).valid()).isTrue();
    }

    private GeneratedQuizDto quizWithKeyword(String keyword) throws Exception {
        String json = """
                {
                  "skip": false,
                  "question": "기준금리와 무관한 일반 질문은 무엇인가?",
                  "choices": [
                    {"orderNum": 1, "content": "정답 보기입니다", "isAnswer": true},
                    {"orderNum": 2, "content": "오답 보기 하나입니다", "isAnswer": false},
                    {"orderNum": 3, "content": "오답 보기 둘입니다", "isAnswer": false},
                    {"orderNum": 4, "content": "오답 보기 셋입니다", "isAnswer": false}
                  ],
                  "explanation": "정답 해설입니다.",
                  "keyword": "%s"
                }
                """.formatted(keyword);
        return objectMapper.readValue(json, GeneratedQuizDto.class);
    }

    private GeneratedQuizDto quiz(String question, String answer, List<String> distractors) throws Exception {
        String json = """
                {
                  "skip": false,
                  "question": "%s",
                  "choices": [
                    {"orderNum": 1, "content": "%s", "isAnswer": true},
                    {"orderNum": 2, "content": "%s", "isAnswer": false},
                    {"orderNum": 3, "content": "%s", "isAnswer": false},
                    {"orderNum": 4, "content": "%s", "isAnswer": false}
                  ],
                  "explanation": "정답 해설입니다.",
                  "keyword": "핵심 용어: 한 줄 설명"
                }
                """.formatted(question, answer,
                distractors.get(0), distractors.get(1), distractors.get(2));
        return objectMapper.readValue(json, GeneratedQuizDto.class);
    }
}
