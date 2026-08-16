package com.example.pinq_backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code truncate} 가 서로게이트 쌍을 쪼개지 않는지 고정한다.
 *
 * substring(0, max) 는 UTF-16 코드 유닛 기준이라, 경계가 이모지 같은 서로게이트 쌍
 * 중간이면 짝 없는 high surrogate 가 남는다. MySQL utf8mb4 는 그런 값을
 * "Incorrect string value" 로 거부하고, 그 예외는 recorder 가 삼키므로 행이 조용히
 * 사라진다 — 자르기가 막으려던 바로 그 일이다.
 */
class QuizGenerationAttemptTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 6, 0);

    @Test
    @DisplayName("서로게이트 쌍 경계에서 자르면 한 글자 더 줄여 짝 없는 서로게이트를 남기지 않는다")
    void truncate_doesNotSplitSurrogatePair() {
        // "🎉"(U+1F389)는 UTF-16 에서 서로게이트 쌍(2 코드 유닛)이다.
        String emoji = "🎉";
        // KEYWORD_MAX(64)-1 개의 'a' + 서로게이트 쌍 앞부분만 걸치도록 만든다.
        String value = "a".repeat(63) + emoji; // length = 63 + 2 = 65, KEYWORD_MAX=64 라면 경계가 서로게이트 중간

        QuizGenerationAttempt attempt = new QuizGenerationAttempt(
                NOW, "INTEREST_RATE", "REGULAR",
                value, null, null,
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null);

        String truncated = attempt.getSearchKeyword();

        // 64번째 코드 유닛(인덱스 63)이 high surrogate 이므로 63자로 한 번 더 줄어야 한다
        assertThat(truncated).hasSize(63);
        assertThat(truncated).isEqualTo("a".repeat(63));
        // 잘린 결과에 짝 없는 서로게이트가 없어야 한다
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("경계가 서로게이트 쌍 중간이 아니면 그대로 max 길이로 자른다")
    void truncate_cutsExactlyAtMax_whenBoundaryIsNotInsideSurrogatePair() {
        String value = "a".repeat(100);

        QuizGenerationAttempt attempt = new QuizGenerationAttempt(
                NOW, "INTEREST_RATE", "REGULAR",
                value, null, null,
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null);

        assertThat(attempt.getSearchKeyword()).hasSize(64);
    }

    @Test
    @DisplayName("max 이하 길이는 그대로 유지된다")
    void truncate_keepsValueUnchanged_whenWithinLimit() {
        String value = "짧은키워드";

        QuizGenerationAttempt attempt = new QuizGenerationAttempt(
                NOW, "INTEREST_RATE", "REGULAR",
                value, null, null,
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null);

        assertThat(attempt.getSearchKeyword()).isEqualTo(value);
    }
}
