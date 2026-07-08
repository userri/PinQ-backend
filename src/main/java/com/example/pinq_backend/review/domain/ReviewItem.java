package com.example.pinq_backend.review.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import com.example.pinq_backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 오답 기반 간격 반복(spaced repetition) 복습 항목 — "잔디에 물 주기".
 *
 * 생명주기:
 *  - 사용자가 퀴즈를 틀리면 stage 0, due = 오답일 + 3일로 등록된다.
 *  - 복습에서 맞히면 stage 가 오르고 다음 주기가 길어진다: 3일 → 7일 → 14일.
 *  - 마지막 단계(stage 2)에서 맞히면 '졸업' — row 를 삭제한다.
 *    (오답 이력 원본은 user_quiz_attempt 에 남아 있으므로 정보 손실 없음)
 *  - 복습에서 또 틀리면 stage 0, due = 오늘 + 3일로 리셋된다.
 *
 * 설계 노트:
 *  - quizId 는 FK 없는 플레인 컬럼 (UserBookmark 와 동일 패턴).
 *    퀴즈 재생성/삭제 시에도 제약 충돌이 없고, 조회 시 사라진 퀴즈는
 *    ReviewService 가 lazy 하게 정리한다.
 *  - 복습 채점은 첫 시도 통계(정답률/스트릭)에 반영하지 않는다 —
 *    "같은 문제 재풀이는 통계에 미반영" 기존 정책과 일관.
 */
@Entity
@Table(
    name = "review_item",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_review_item",
        columnNames = {"user_id", "quiz_id"}
    ),
    indexes = @Index(name = "idx_review_item_user_due", columnList = "user_id, due_date")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewItem extends BaseTimeEntity {

    /** 단계별 복습 간격(일). index = stage. 마지막 단계에서 정답이면 졸업. */
    static final int[] INTERVAL_DAYS = {3, 7, 14};

    /** 졸업 직전 마지막 단계. */
    public static final int MAX_STAGE = INTERVAL_DAYS.length - 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    /** 현재 복습 단계 (0부터). 단계가 오를수록 다음 복습까지의 간격이 길어진다. */
    @Column(name = "stage", nullable = false)
    private int stage;

    /** 이 날짜부터 복습 대상이 된다 (KST 기준 LocalDate). */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** 오답 발생 → 첫 복습 예약. */
    public static ReviewItem enqueue(User user, Long quizId, LocalDate wrongAnsweredOn) {
        ReviewItem item = new ReviewItem();
        item.user = user;
        item.quizId = quizId;
        item.stage = 0;
        item.dueDate = wrongAnsweredOn.plusDays(INTERVAL_DAYS[0]);
        return item;
    }

    /**
     * 복습 정답 처리.
     *
     * @return true 면 졸업(호출자가 row 를 삭제해야 함), false 면 다음 단계로 진행됨
     */
    public boolean advanceOrGraduate(LocalDate reviewedOn) {
        if (stage >= MAX_STAGE) {
            return true; // 마지막 단계 통과 — 졸업
        }
        stage += 1;
        dueDate = reviewedOn.plusDays(INTERVAL_DAYS[stage]);
        return false;
    }

    /** 복습 오답 처리 — 처음부터 다시. */
    public void reset(LocalDate reviewedOn) {
        stage = 0;
        dueDate = reviewedOn.plusDays(INTERVAL_DAYS[0]);
    }

    /** 이 항목이 주어진 날짜에 복습 대상인가. */
    public boolean isDueOn(LocalDate date) {
        return !dueDate.isAfter(date);
    }
}
