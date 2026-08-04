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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 오답 기반 간격 반복(spaced repetition) 복습 항목 — "잔디에 물 주기".
 *
 * 생명주기:
 *  - 사용자가 퀴즈를 틀리면 stage 0, due = 오답일 + 3일로 등록된다.
 *  - 복습에서 맞히면 stage 가 오르고 다음 주기가 길어진다: 3일 → 7일 → 14일.
 *  - 마지막 단계(stage 2)에서 맞히면 '졸업' — row 는 남기고 graduated_at 을 기록한다.
 *    졸업한 항목은 due 조회에서 제외되며, 재오답해도 복습 큐에 재진입하지 않는다
 *    (다 키운 나무는 시들지 않는다 — 영구 성취). 이 row 가 "나무 목록"의 원천이다.
 *  - 복습에서 또 틀리면 <b>stage 는 그대로 두고</b> due 만 오늘 + 3일로 당긴다.
 *    (진척은 되돌리지 않는다 — 자세한 근거는 {@link #retryLater} 주석)
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

    /** 이 항목에 물을 준 총 횟수 (복습 시도 수). 정답/오답 무관하게 오른다. */
    @Column(name = "water_count", nullable = false)
    private int waterCount;

    /** 흡수된 물 — 복습에서 맞힌 횟수. */
    @Column(name = "absorbed_count", nullable = false)
    private int absorbedCount;

    /** 졸업 시각. null 이면 아직 자라는 중. 졸업 후에는 복습 대상에서 영구 제외. */
    @Column(name = "graduated_at")
    private LocalDateTime graduatedAt;

    /** 오답 발생 → 첫 복습 예약. */
    public static ReviewItem enqueue(User user, Long quizId, LocalDate wrongAnsweredOn) {
        ReviewItem item = new ReviewItem();
        item.user = user;
        item.quizId = quizId;
        item.stage = 0;
        item.dueDate = wrongAnsweredOn.plusDays(INTERVAL_DAYS[0]);
        return item;
    }

    /** 복습 시도 1회 기록 — "물 주기". 채점 결과와 무관하게 항상 호출한다. */
    public void water(boolean correct) {
        waterCount += 1;
        if (correct) {
            absorbedCount += 1;
        }
    }

    /** 졸업 처리 — row 를 남긴 채 시각만 기록한다 (나무 목록의 원천). */
    public void graduate(LocalDateTime now) {
        graduatedAt = now;
    }

    public boolean isGraduated() {
        return graduatedAt != null;
    }

    /**
     * 복습 정답 처리.
     *
     * @return true 면 졸업(호출자가 graduate() 를 호출해야 함), false 면 다음 단계로 진행됨
     */
    public boolean advanceOrGraduate(LocalDate reviewedOn) {
        if (stage >= MAX_STAGE) {
            return true; // 마지막 단계 통과 — 졸업
        }
        stage += 1;
        dueDate = reviewedOn.plusDays(INTERVAL_DAYS[stage]);
        return false;
    }

    /**
     * 복습 오답 처리 — <b>진척(stage)은 그대로 두고 다시 만날 날짜만 당긴다.</b>
     *
     * 종전에는 stage 를 0 으로 되돌렸다(SM-2 관습). 폐기한 이유:
     *  - 리셋의 정당한 목적은 "방금 틀린 문제를 14일 뒤에 보지 않는다"는 <b>스케줄링</b>인데,
     *    그건 dueDate 를 당기는 것만으로 100% 달성된다.
     *  - stage 는 스케줄링 티어인 동시에 화면의 성장 게이지라, 리셋이 사용자에게는
     *    "쌓은 진척이 사라졌다"는 <b>보상 박탈</b>로 읽힌다. 벌칙은 하루 한 번 오는
     *    습관 앱에서 동기가 되지 않고 복습 회피를 만든다.
     *  - 졸업 조건(3·7·14일 간격으로 세 번 정답, 최소 24일)은 그대로다. 틀리면 3일씩 밀릴 뿐.
     */
    public void retryLater(LocalDate reviewedOn) {
        dueDate = reviewedOn.plusDays(INTERVAL_DAYS[0]);
    }

    /** 이 항목이 주어진 날짜에 복습 대상인가. */
    public boolean isDueOn(LocalDate date) {
        return !dueDate.isAfter(date);
    }
}
