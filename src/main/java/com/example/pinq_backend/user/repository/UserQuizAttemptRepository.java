package com.example.pinq_backend.user.repository;

import com.example.pinq_backend.user.domain.UserQuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQuizAttemptRepository extends JpaRepository<UserQuizAttempt, Long> {

    boolean existsByUserIdAndQuizId(Long userId, Long quizId);

    @Modifying
    @Query("DELETE FROM UserQuizAttempt a WHERE a.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
