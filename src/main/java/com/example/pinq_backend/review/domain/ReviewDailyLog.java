package com.example.pinq_backend.review.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import com.example.pinq_backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 일별 복습 기록.
 *
 * 왜 필요한가: 복습은 user_quiz_attempt 를 만들지 않으므로(첫 시도만 기록하는 정책),
 * 복습만 한 날은 잔디밭에 아무 흔적도 남지 않았다. 이 로그로 "물은 줬다"는 사실을
 * 날짜 단위로 남겨, 복습만 한 날도 연한 잔디(level 1)가 심어지게 한다.
 *
 * 잔디 농도는 여전히 '신규 학습'의 지표다 — 복습을 아무리 많이 해도 level 1 이며,
 * 스트릭(데일리 퀴즈 습관)에는 전혀 반영되지 않는다.
 */
@Entity
@Table(
    name = "review_daily_log",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_review_daily_log",
        columnNames = {"user_id", "review_date"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDailyLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "review_date", nullable = false)
    private LocalDate reviewDate;

    /** 그날 복습한 문제 수 (정답·오답 합). */
    @Column(name = "reviewed_count", nullable = false)
    private int reviewedCount;

    /** 그날 복습에서 맞힌 수. */
    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    /** 그날의 첫 복습 — 카운트 1부터 시작. */
    public static ReviewDailyLog firstReviewOfDay(User user, LocalDate reviewDate, boolean correct) {
        ReviewDailyLog log = new ReviewDailyLog();
        log.user = user;
        log.reviewDate = reviewDate;
        log.reviewedCount = 1;
        log.correctCount = correct ? 1 : 0;
        return log;
    }

    /** 같은 날 추가 복습 — 카운트 증가. */
    public void record(boolean correct) {
        reviewedCount += 1;
        if (correct) {
            correctCount += 1;
        }
    }
}
