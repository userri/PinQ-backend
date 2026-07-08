package com.example.pinq_backend.review.repository;

import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    Optional<ReviewItem> findByUserIdAndQuizId(Long userId, Long quizId);

    /** 오늘 복습 대상 (due 가 오늘이거나 지난 것 — 밀린 복습 포함). */
    List<ReviewItem> findAllByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(Long userId, LocalDate date);

    /** 다음 예정 복습일 계산용 — 아직 due 가 안 된 것 중 가장 이른 것. */
    Optional<ReviewItem> findFirstByUserIdAndDueDateAfterOrderByDueDateAsc(Long userId, LocalDate date);

    long countByUserIdAndDueDateLessThanEqual(Long userId, LocalDate date);
}
