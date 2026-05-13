package com.example.pinq_backend.quiz.repository;

import com.example.pinq_backend.quiz.domain.Quiz;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    /**
     * 오늘의 퀴즈 목록을 id 오름차순으로 반환.
     *
     * Phase 1: 단순히 전체 4문제를 그대로 반환.
     * Phase 2: 일자 기반 회전 로직(예: 날짜 % 전체 문항수)으로 교체 예정.
     */
    List<Quiz> findAllByOrderByIdAsc();
}
