package com.example.pinq_backend.user.repository;

import com.example.pinq_backend.user.domain.UserQuizAttempt;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQuizAttemptRepository extends JpaRepository<UserQuizAttempt, Long> {

    boolean existsByUserIdAndQuizId(Long userId, Long quizId);

    Optional<UserQuizAttempt> findByUserIdAndQuizId(Long userId, Long quizId);

    long countByUserId(Long userId);

    long countByUserIdAndFirstCorrectTrue(Long userId);

    /** 사용자의 전체 풀이 이력 — 최신순. */
    List<UserQuizAttempt> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 사용자의 오답 이력 (첫 시도에서 틀린 것만) — 최신순. */
    List<UserQuizAttempt> findByUserIdAndFirstCorrectFalseOrderByCreatedAtDesc(Long userId);

    /**
     * 날짜 범위 내 각 날짜별 처음 시도 정답 수를 집계한다.
     *
     * Native query 사용 — JPQL CAST AS localdate 는 Hibernate 전용 확장이므로
     * Dialect 의존성 제거를 위해 DATE() 함수 기반 native SQL 로 작성.
     * 반환: [DATE(java.sql.Date), COUNT(Number)] 형태의 Object[] 리스트.
     */
    @Query(value = """
        SELECT DATE(a.created_at), COUNT(*)
        FROM user_quiz_attempt a
        WHERE a.user_id = :userId
          AND a.first_correct = true
          AND DATE(a.created_at) BETWEEN :from AND :to
        GROUP BY DATE(a.created_at)
        """, nativeQuery = true)
    List<Object[]> countFirstCorrectByDateBetween(
        @Param("userId") Long userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /**
     * 날짜 범위 내 각 날짜별 첫 시도 수를 집계한다 (정답 여부 무관).
     *
     * Native query 사용 — 위 countFirstCorrectByDateBetween 과 동일한 이유.
     * 반환: [DATE(java.sql.Date), COUNT(Number)] 형태의 Object[] 리스트.
     */
    @Query(value = """
        SELECT DATE(a.created_at), COUNT(*)
        FROM user_quiz_attempt a
        WHERE a.user_id = :userId
          AND DATE(a.created_at) BETWEEN :from AND :to
        GROUP BY DATE(a.created_at)
        """, nativeQuery = true)
    List<Object[]> countAttemptsByDateBetween(
        @Param("userId") Long userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /**
     * 사용자가 첫 시도한 날짜 전체 목록을 반환한다.
     * users.current_streak / max_streak 복구와 동기화의 기준 데이터다.
     */
    @Query(value = """
        SELECT DISTINCT DATE(a.created_at)
        FROM user_quiz_attempt a
        WHERE a.user_id = :userId
        ORDER BY DATE(a.created_at)
        """, nativeQuery = true)
    List<Object> findAttemptDatesByUserIdOrderByDateAsc(@Param("userId") Long userId);

    /**
     * 특정 quizId 목록에 속하는 attempt 만 조회한다.
     * 전체 attempt 를 로드한 뒤 필터링하는 방식 대비 I/O 절감.
     */
    List<UserQuizAttempt> findByUserIdAndQuizIdIn(Long userId, List<Long> quizIds);

    /**
     * 카테고리별 첫 시도 수·정답 수 집계 (취약 개념 진단용).
     *
     * quiz.category 는 라벨 오염 수정 이후의 신뢰 원천이지만 구 레코드는 NULL 이라
     * article.category 로 폴백한다 (Quiz.getCategory() 와 동일한 규칙을 SQL 로 재현).
     * 반환: [category(String), total(Number), correct(Number)] Object[] 리스트.
     */
    @Query(value = """
        SELECT COALESCE(q.category, na.category) AS category,
               COUNT(*) AS total,
               SUM(CASE WHEN a.first_correct = true THEN 1 ELSE 0 END) AS correct
        FROM user_quiz_attempt a
        JOIN quiz q ON q.id = a.quiz_id
        LEFT JOIN news_article na ON na.id = q.article_id
        WHERE a.user_id = :userId
        GROUP BY COALESCE(q.category, na.category)
        """, nativeQuery = true)
    List<Object[]> countByCategory(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM UserQuizAttempt a WHERE a.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
