package com.example.pinq_backend.user.repository;

import com.example.pinq_backend.user.domain.UserQuizAttempt;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQuizAttemptRepository extends JpaRepository<UserQuizAttempt, Long> {

    boolean existsByUserIdAndQuizId(Long userId, Long quizId);

    /**
     * 날짜 범위 내 각 날짜별 처음 시도 정답 수를 집계한다.
     *
     * UserQuizAttempt.createdAt(BaseTimeEntity) 기준으로 날짜를 구한다.
     * firstCorrect=true 인 행만 카운트 → 해당 날 첫 시도에서 맞힌 문제 수.
     *
     * 반환: [날짜(LocalDate), 정답수(Long)] 형태의 Object[] 리스트.
     */
    @Query("""
        SELECT CAST(a.createdAt AS localdate), COUNT(a)
        FROM UserQuizAttempt a
        WHERE a.user.id = :userId
          AND a.firstCorrect = true
          AND CAST(a.createdAt AS localdate) BETWEEN :from AND :to
        GROUP BY CAST(a.createdAt AS localdate)
        """)
    List<Object[]> countFirstCorrectByDateBetween(
        @Param("userId") Long userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    @Modifying
    @Query("DELETE FROM UserQuizAttempt a WHERE a.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
