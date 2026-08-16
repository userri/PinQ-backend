package com.example.pinq_backend.audit.repository;

import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizGenerationAttemptRepository
        extends JpaRepository<QuizGenerationAttempt, Long> {

    /**
     * 날짜 × 카테고리 × stage × reason 롤업 — 검수 회차의 조회 패턴 그대로다.
     *
     * stage 와 reason 을 따로 묶는 것이 핵심이다. 한 축으로 뭉치면
     * "VERIFY 단계 손실이 전체의 몇 %인가"를 문자열 조작 없이는 못 센다.
     */
    @Query("""
            select a.occurredOn as day, a.category as category,
                   a.stage as stage, a.reason as reason, count(a) as attempts
            from QuizGenerationAttempt a
            where a.occurredOn >= :from
            group by a.occurredOn, a.category, a.stage, a.reason
            order by a.occurredOn asc, a.category asc, a.stage asc
            """)
    List<DailyRow> rollupSince(@Param("from") LocalDate from);

    /** 하루치 원시 행 — 기사 제목으로 기사 풀 오염을 눈으로 확인하는 경로 */
    List<QuizGenerationAttempt> findByOccurredOnOrderByOccurredAtAsc(LocalDate day);

    interface DailyRow {
        LocalDate getDay();
        String getCategory();
        String getStage();
        String getReason();
        long getAttempts();
    }
}
