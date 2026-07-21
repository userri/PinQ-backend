package com.example.pinq_backend.review.repository;

import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    Optional<ReviewItem> findByUserIdAndQuizId(Long userId, Long quizId);

    /** 오늘 복습 대상 (due 가 오늘이거나 지난 것 — 밀린 복습 포함). 졸업한 나무는 제외. */
    List<ReviewItem> findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(
            Long userId, LocalDate date);

    /** 다음 예정 복습일 계산용 — 아직 due 가 안 된 것 중 가장 이른 것. 졸업 제외. */
    Optional<ReviewItem> findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(
            Long userId, LocalDate date);

    /** 정원 조회용 — 자라는 항목 + 졸업한 나무 전부. */
    List<ReviewItem> findAllByUserId(Long userId);

    /** 오답노트 화면의 복습 상태 join 용 — quizId 묶음 batch 조회. */
    List<ReviewItem> findAllByUserIdAndQuizIdIn(Long userId, Collection<Long> quizIds);
}
