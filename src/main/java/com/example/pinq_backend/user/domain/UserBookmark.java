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
 * 사용자별 퀴즈 북마크.
 *
 * 정책:
 *  - 같은 (user, quiz) 조합은 단 한 번만 기록된다 (UK 보장).
 *  - 북마크는 풀이 이력(UserQuizAttempt) 과 독립적으로 관리된다.
 *    이론적으로는 풀지 않은 문제도 북마크 가능하지만, 현재 클라이언트 플로우에서는
 *    "풀어본 문제만 노출되므로" 사실상 attempt 가 있는 quiz 만 북마크된다.
 *  - 북마크 해제는 row 삭제 — 단순 토글. attempt 데이터는 남아있으므로 언제든 복구 가능.
 */
@Entity
@Table(
    name = "user_bookmark",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_bookmark",
        columnNames = {"user_id", "quiz_id"}
    ),
    indexes = @Index(name = "idx_user_bookmark_user", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBookmark extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    public static UserBookmark create(User user, Long quizId) {
        UserBookmark b = new UserBookmark();
        b.user = user;
        b.quizId = quizId;
        return b;
    }
}
