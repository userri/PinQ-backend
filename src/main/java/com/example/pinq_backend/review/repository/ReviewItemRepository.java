package com.example.pinq_backend.review.repository;

import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    Optional<ReviewItem> findByUserIdAndQuizId(Long userId, Long quizId);

    /**
     * 오늘 복습 세트 선발 (due 가 오늘이거나 지난 것 — 밀린 복습 포함). 졸업한 나무는 제외.
     *
     * 정렬이 stage 내림차순인 것은 의도적이다 — 졸업에 가까운 항목부터 물을 줘야
     * 나무가 빨리 나온다. 같은 단계 안에서는 오래 밀린 것 먼저.
     * 호출자가 Limit 으로 하루치를 잘라 세션을 완주 가능한 크기로 유지한다.
     */
    List<ReviewItem> findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByStageDescDueDateAsc(
            Long userId, LocalDate date, Limit limit);

    /** due 총 개수 — 큐가 잘렸는지 판정하고 정원 배지 숫자를 계산하는 데 쓴다. */
    long countByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqual(Long userId, LocalDate date);

    /** 다음 예정 복습일 계산용 — 아직 due 가 안 된 것 중 가장 이른 것. 졸업 제외. */
    Optional<ReviewItem> findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(
            Long userId, LocalDate date);

    /** 정원 조회용 — 자라는 항목 + 졸업한 나무 전부. */
    List<ReviewItem> findAllByUserId(Long userId);

    /** 오답노트 화면의 복습 상태 join 용 — quizId 묶음 batch 조회. */
    List<ReviewItem> findAllByUserIdAndQuizIdIn(Long userId, Collection<Long> quizIds);
}
