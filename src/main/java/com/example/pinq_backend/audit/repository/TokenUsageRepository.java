package com.example.pinq_backend.audit.repository;

import com.example.pinq_backend.audit.domain.TokenUsage;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    /**
     * 날짜 × kind 롤업 — 검수 회차의 조회 패턴 그대로다.
     *
     * 호출 건수(calls)와 캐시 적중 회차 수(cacheHits)를 함께 낸다.
     * 캐싱 성과 판정은 토큰 합이 아니라 <b>cacheHits / calls</b> 로 한다 —
     * prompt 합은 캐시가 먹으면 당연히 줄어 절감처럼 보인다.
     */
    @Query("""
            select u.occurredOn as day, u.kind as kind, count(u) as calls,
                   sum(case when u.cacheReadTokens > 0 then 1 else 0 end) as cacheHits,
                   sum(u.promptTokens) as promptTokens,
                   sum(u.completionTokens) as completionTokens,
                   sum(u.cacheWriteTokens) as cacheWriteTokens,
                   sum(u.cacheReadTokens) as cacheReadTokens,
                   sum(u.totalTokens) as totalTokens
            from TokenUsage u
            where u.occurredOn >= :from
            group by u.occurredOn, u.kind
            order by u.occurredOn asc, u.kind asc
            """)
    List<DailyRow> rollupSince(@Param("from") LocalDate from);

    interface DailyRow {
        LocalDate getDay();
        String getKind();
        long getCalls();
        long getCacheHits();
        long getPromptTokens();
        long getCompletionTokens();
        long getCacheWriteTokens();
        long getCacheReadTokens();
        long getTotalTokens();
    }
}
