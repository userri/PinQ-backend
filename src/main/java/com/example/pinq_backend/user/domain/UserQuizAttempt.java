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

    public static UserQuizAttempt create(User user, Long quizId, boolean isCorrect) {
        UserQuizAttempt a = new UserQuizAttempt();
        a.user = user;
        a.quizId = quizId;
        a.firstCorrect = isCorrect;
        return a;
    }
}
