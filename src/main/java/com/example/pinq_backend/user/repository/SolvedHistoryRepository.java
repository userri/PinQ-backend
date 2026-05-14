package com.example.pinq_backend.user.repository;

import com.example.pinq_backend.user.domain.SolvedHistory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolvedHistoryRepository extends JpaRepository<SolvedHistory, Long> {

    Optional<SolvedHistory> findByUserIdAndSolvedDate(Long userId, LocalDate solvedDate);

    /** 전체 풀이 기록 (totalSolved / correctRate 계산용). */
    List<SolvedHistory> findByUserId(Long userId);

    /** 최근 N일 기록 (activityGrid 계산용). */
    @Query("""
        SELECT h FROM SolvedHistory h
        WHERE h.user.id = :userId
          AND h.solvedDate BETWEEN :from AND :to
        """)
    List<SolvedHistory> findByUserIdAndSolvedDateBetween(
        @Param("userId") Long userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /** 회원탈퇴 시 해당 유저의 풀이 기록 전체 삭제. */
    @Modifying
    @Query("DELETE FROM SolvedHistory h WHERE h.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
