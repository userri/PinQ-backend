package com.example.pinq_backend.user.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 퀴즈 최초 시도 기록.
 *
 * 정책: 같은 (user, quiz) 조합은 단 한 번만 기록된다.
 *   → 같은 문제를 여러 번 풀어도 totalSolved/correctRate 통계에는 첫 시도만 반영.
 *   → 복습은 자유롭게 가능하지만 정답률 부풀리기를 막는다.
 *
 * UserService.recordAnswer 가 채점할 때마다 호출되어,
 *  - 이미 row 가 있으면 통계 갱신 없이 종료
 *  - row 가 없으면 새로 만들고 SolvedHistory/streak 도 함께 갱신
 *
 * Phase 3 (OAuth) 에서는 user_id 가 인증 컨텍스트에서 채워진다.
 *
 * Phase 4 변경점:
 *  - firstSelectedChoiceId 컬럼 추가: 사용자가 첫 시도에 어떤 선택지를 골랐는지 기록한다.
 *    오답노트/풀이이력 화면에서 "내가 고른 답"을 표시하기 위해 필요.
 *    legacy 데이터는 NULL — 클라이언트에서 "기록 없음" 으로 표시.
 */
@Entity
@Table(
    name = "user_quiz_attempt",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_quiz_attempt",
        columnNames = {"user_id", "quiz_id"}
    ),
    indexes = @Index(name = "idx_user_quiz_attempt_user", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserQuizAttempt extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    @Column(name = "first_correct", nullable = false)
    private boolean firstCorrect;

    /** 첫 시도에 사용자가 고른 선택지 ID. legacy 데이터는 NULL 가능. */
    @Column(name = "first_selected_choice_id")
    private Long firstSelectedChoiceId;

    /**
     * 첫 시도 풀이 소요 시간(ms). 클라이언트가 포그라운드 시간만 측정해 전송.
     * 이상치(앱 방치)는 저장 시 자르지 않고 원본을 보존한다 — 집계 시점에 절단(LEAST 캡).
     * legacy 데이터·미전송 클라이언트는 NULL.
     */
    @Column(name = "first_elapsed_ms")
    private Integer firstElapsedMs;

    /** 문제 피드백: 1=좋아요, -1=별로예요, NULL=미응답. 해설 화면의 선택적 1탭. */
    @Column(name = "feedback")
    private Integer feedback;

    public static UserQuizAttempt create(
        User user,
        Long quizId,
        Long selectedChoiceId,
        boolean isCorrect
    ) {
        return create(user, quizId, selectedChoiceId, isCorrect, null);
    }

    public static UserQuizAttempt create(
        User user,
        Long quizId,
        Long selectedChoiceId,
        boolean isCorrect,
        Integer elapsedMs
    ) {
        UserQuizAttempt a = new UserQuizAttempt();
        a.user = user;
        a.quizId = quizId;
        a.firstSelectedChoiceId = selectedChoiceId;
        a.firstCorrect = isCorrect;
        a.firstElapsedMs = elapsedMs;
        return a;
    }

    /** 해설 화면 피드백 기록. 재탭 시 마지막 값으로 덮어쓴다 (멱등). */
    public void recordFeedback(int value) {
        this.feedback = value;
    }
}
