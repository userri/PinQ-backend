package com.example.pinq_backend.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.user.domain.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 간격 반복 상태머신 검증: 3일 → 7일 → 14일 → 졸업, 오답 시 리셋.
 */
class ReviewItemTest {

    private static final LocalDate DAY0 = LocalDate.of(2026, 7, 8);
    private final User user = User.builder().nickname("tester").build();

    @Test
    @DisplayName("오답 등록 시 3일 뒤가 첫 복습일이 된다")
    void enqueue_firstReviewAfter3Days() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, DAY0);

        assertThat(item.getStage()).isZero();
        assertThat(item.getDueDate()).isEqualTo(DAY0.plusDays(3));
        assertThat(item.isDueOn(DAY0.plusDays(2))).isFalse();
        assertThat(item.isDueOn(DAY0.plusDays(3))).isTrue();
        assertThat(item.isDueOn(DAY0.plusDays(10))).isTrue(); // 밀린 복습도 due
    }

    @Test
    @DisplayName("복습 정답 시 주기가 3→7→14일로 길어지고, 마지막 단계 통과 시 졸업한다")
    void advance_lengthensInterval_thenGraduates() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, DAY0);

        LocalDate firstReview = DAY0.plusDays(3);
        boolean graduated1 = item.advanceOrGraduate(firstReview);
        assertThat(graduated1).isFalse();
        assertThat(item.getStage()).isEqualTo(1);
        assertThat(item.getDueDate()).isEqualTo(firstReview.plusDays(7));

        LocalDate secondReview = firstReview.plusDays(7);
        boolean graduated2 = item.advanceOrGraduate(secondReview);
        assertThat(graduated2).isFalse();
        assertThat(item.getStage()).isEqualTo(2);
        assertThat(item.getDueDate()).isEqualTo(secondReview.plusDays(14));

        LocalDate thirdReview = secondReview.plusDays(14);
        boolean graduated3 = item.advanceOrGraduate(thirdReview);
        assertThat(graduated3).isTrue(); // 3회 연속 기억 성공 — 졸업
    }

    @Test
    @DisplayName("복습 오답 시 처음(3일 주기)부터 다시 시작한다")
    void reset_restartsFromStageZero() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, DAY0);
        item.advanceOrGraduate(DAY0.plusDays(3)); // stage 1

        LocalDate failedOn = DAY0.plusDays(10);
        item.reset(failedOn);

        assertThat(item.getStage()).isZero();
        assertThat(item.getDueDate()).isEqualTo(failedOn.plusDays(3));
    }

    @Test
    @DisplayName("물 주기: 시도마다 waterCount 가 오르고, 정답이면 absorbedCount 도 오른다")
    void water_countsAttemptsAndAbsorbs() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, DAY0);

        item.water(true);
        item.water(false);
        item.water(true);

        assertThat(item.getWaterCount()).isEqualTo(3);
        assertThat(item.getAbsorbedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("졸업: graduate 는 graduatedAt 을 기록하고 isGraduated 가 true 가 된다")
    void graduate_marksGraduatedAt() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, DAY0);
        LocalDateTime now = DAY0.atStartOfDay();

        assertThat(item.isGraduated()).isFalse();
        item.graduate(now);

        assertThat(item.isGraduated()).isTrue();
        assertThat(item.getGraduatedAt()).isEqualTo(now);
    }
}
