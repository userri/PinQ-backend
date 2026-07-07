package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 테스트 케이스의 문장 쌍은 전부 운영 DB(2026-04-26~06-09, 172문항)에서 가져온
 * 실제 중복/비중복 사례다. 임계값 캘리브레이션의 근거이기도 하다.
 */
class QuizSimilarityCheckerTest {

    private final QuizSimilarityChecker checker = new QuizSimilarityChecker();

    @Test
    @DisplayName("완전히 동일한 문항은 중복으로 잡는다 (운영에서 이틀 연속 동일 출제된 사례)")
    void identicalQuestion_isDetected() {
        String question = "국채 금리가 상승할 때 주식 시장에 미치는 영향은 무엇인가요?";

        Optional<QuizSimilarityChecker.Match> match =
                checker.findMostSimilar(question, List.of(question));

        assertThat(match).isPresent();
        assertThat(match.get().existingQuestion()).isEqualTo(question);
    }

    @Test
    @DisplayName("어미·수식어만 바뀐 변주는 중복으로 잡는다 (3주간 14회 출제된 '미국채→주식' 계열)")
    void paraphrasedVariant_isDetected() {
        String history = "미국 국채 금리가 상승하면 일반적으로 주식 시장에 미치는 영향은 무엇인가요?";
        String candidate = "미국 국채 금리가 상승할 경우 주식 시장에 미치는 영향은 무엇일까요?";

        assertThat(checker.findMostSimilar(candidate, List.of(history))).isPresent();
    }

    @Test
    @DisplayName("주어에 단어가 추가된 변주도 중복으로 잡는다 ('금리'→'기준금리' 변동금리 이자 부담 계열)")
    void subjectVariant_isDetected() {
        String history = "금리가 인상되면 변동금리 대출의 이자 부담은 어떻게 될까요?";
        String candidate = "기준금리가 인상되면 변동금리 대출의 이자 부담은 어떻게 변할까요?";

        assertThat(checker.findMostSimilar(candidate, List.of(history))).isPresent();
    }

    @Test
    @DisplayName("어휘 겹침이 낮은 의미적 변주는 렉시컬 층에서 잡지 않는다 (프롬프트 이력 주입 층에 위임)")
    void lowLexicalOverlapSemanticVariant_isDelegatedToSemanticLayer() {
        // J=0.23/D=0.68 — 이 구간을 잡으려고 임계값을 내리면 별개 개념 쌍(D 0.6~0.71)까지
        // 오탐으로 폐기되는 것이 리뷰에서 확인되어, 의도적으로 잡지 않는다.
        String history = "금리가 상승하면 주식시장에 미치는 일반적인 영향은 무엇인가요?";
        String candidate = "국채 금리가 상승할 경우 주식 시장에 미치는 영향은 무엇인가?";

        assertThat(checker.findMostSimilar(candidate, List.of(history))).isEmpty();
    }

    @Test
    @DisplayName("짧은 정의형 문항은 접미가 겹쳐도 잡지 않는다 (다른 개념의 정의 문제)")
    void shortDefinitionQuestions_differentConcepts_isNotDetected() {
        // "~금리란 무엇인가" 공유 접미만으로 bigram Dice 가 0.78~0.82 까지 치솟는 사례.
        // 짧은 문장 길이 가드(SOLO_DICE_MIN_LENGTH)가 이 오탐을 막는다.
        assertThat(checker.findMostSimilar(
                "콜금리란 무엇인가?", List.of("기준금리란 무엇인가?"))).isEmpty();
        assertThat(checker.findMostSimilar(
                "가산금리란 무엇인가요?", List.of("기준금리란 무엇인가요?"))).isEmpty();
    }

    @Test
    @DisplayName("'~에 미치는 영향은?' 템플릿만 공유하는 별개 개념 문제는 잡지 않는다")
    void templateOnlyOverlap_isNotDetected() {
        String history = "기준금리 인상이 예금 금리에 미치는 영향은 무엇인가?";
        String candidate = "포워드 가이던스가 금리에 미치는 영향은 무엇인가?";

        assertThat(checker.findMostSimilar(candidate, List.of(history))).isEmpty();
    }

    @Test
    @DisplayName("같은 문형이지만 다른 시장을 묻는 문제는 잡지 않는다")
    void differentMarketSameTemplate_isNotDetected() {
        String history = "주택담보대출 금리 상승이 부동산 시장에 미치는 주요 영향은 무엇인가?";
        String candidate = "금리가 상승할 때 주식 시장에 영향을 미치는 주요 메커니즘은 무엇인가?";

        assertThat(checker.findMostSimilar(candidate, List.of(history))).isEmpty();
    }

    @Test
    @DisplayName("완전히 다른 주제는 잡지 않는다")
    void unrelatedQuestions_isNotDetected() {
        String history = "콜금리와 기준금리의 가장 큰 차이는?";
        String candidate = "액티브 ETF와 패시브 ETF의 가장 큰 차이점은 무엇인가?";

        assertThat(checker.findMostSimilar(candidate, List.of(history))).isEmpty();
    }

    @Test
    @DisplayName("이력에서 가장 유사한 문항을 반환한다")
    void returnsMostSimilarAmongHistory() {
        String exact = "국채 금리가 상승할 때 주식 시장에 미치는 영향은 무엇인가요?";
        String loose = "미국 국채 금리가 상승하면 일반적으로 주식 시장에 미치는 영향은 무엇인가요?";

        Optional<QuizSimilarityChecker.Match> match = checker.findMostSimilar(
                "국채 금리가 상승할 때 주식 시장에 미치는 영향은 무엇인가요?",
                List.of(loose, exact)
        );

        assertThat(match).isPresent();
        assertThat(match.get().existingQuestion()).isEqualTo(exact);
    }

    @Test
    @DisplayName("이력이 비어 있거나 후보가 null/공백이면 잡지 않는다")
    void emptyInputs_returnEmpty() {
        assertThat(checker.findMostSimilar("아무 문제", List.of())).isEmpty();
        assertThat(checker.findMostSimilar(null, List.of("기존 문제"))).isEmpty();
        assertThat(checker.findMostSimilar("  ", List.of("기존 문제"))).isEmpty();
    }
}
