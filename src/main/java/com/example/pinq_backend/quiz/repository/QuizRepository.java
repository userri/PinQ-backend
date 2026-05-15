package com.example.pinq_backend.quiz.repository;

import com.example.pinq_backend.quiz.domain.Quiz;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    /**
     * Phase 2 시드 데이터 조회 (quizDate = null).
     * Phase 3 에서는 날짜 기반 조회로 대체된다.
     */
    @EntityGraph(attributePaths = {"choices", "article"})
    List<Quiz> findAllByOrderByIdAsc();

    /** 특정 날짜의 퀴즈 목록 조회 (Phase 3 일별 퀴즈). */
    @EntityGraph(attributePaths = {"choices", "article"})
    List<Quiz> findAllByQuizDateOrderByIdAsc(LocalDate quizDate);

    /** 특정 날짜의 퀴즈 개수 확인. */
    long countByQuizDate(LocalDate quizDate);

    /** 특정 날짜의 퀴즈 전체 삭제 (재생성 시 사용). */
    List<Quiz> findAllByQuizDate(LocalDate quizDate);
}
