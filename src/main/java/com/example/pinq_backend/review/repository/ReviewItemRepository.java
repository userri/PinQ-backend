package com.example.pinq_backend.review.repository;

import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    Optional<ReviewItem> findByUserIdAndQuizId(Long userId, Long quizId);

    /**
     * 오늘 복습 세트 선발 — due 이면서 <b>오늘 아직 물 주지 않은</b> 항목.
     *
     * lastReviewedOn 조건이 핵심이다. 이게 없으면 한 문제를 풀어도 백로그의 다음 항목이
     * 그 자리를 즉시 메워 큐가 영원히 상한 크기로 유지된다(완주 불가).
     * null 비교는 SQL 3값 논리 때문에 `<>` 만으로는 NULL 행이 탈락하므로 IS NULL 을 함께 쓴다.
     *
     * 정렬이 stage 내림차순인 것은 의도적이다 — 졸업에 가까운 항목부터 물을 줘야
     * 나무가 빨리 나온다. 같은 단계 안에서는 오래 밀린 것 먼저.
     */
    @Query("""
        select r from ReviewItem r
        where r.user.id = :userId
          and r.graduatedAt is null
          and r.dueDate <= :today
          and (r.lastReviewedOn is null or r.lastReviewedOn <> :today)
        order by r.stage desc, r.dueDate asc
        """)
    List<ReviewItem> findTodayQueue(
            @Param("userId") Long userId, @Param("today") LocalDate today, Limit limit);

    /** 오늘 이미 물 준 항목 수 — 하루 상한에서 소진한 몫. 졸업한 항목도 몫을 썼으므로 포함한다. */
    long countByUserIdAndLastReviewedOn(Long userId, LocalDate date);

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
