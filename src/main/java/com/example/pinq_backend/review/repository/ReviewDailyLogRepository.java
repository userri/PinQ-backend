package com.example.pinq_backend.review.repository;

import com.example.pinq_backend.review.domain.ReviewDailyLog;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDailyLogRepository extends JpaRepository<ReviewDailyLog, Long> {

    Optional<ReviewDailyLog> findByUserIdAndReviewDate(Long userId, LocalDate reviewDate);

    /** 잔디밭 기간 내 복습 기록 — 복습만 한 날에 연한 잔디를 심기 위해 사용. */
    List<ReviewDailyLog> findAllByUserIdAndReviewDateBetween(Long userId, LocalDate from, LocalDate to);
}
