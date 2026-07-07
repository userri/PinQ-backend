package com.example.pinq_backend.quiz.repository;

import com.example.pinq_backend.quiz.domain.Quiz;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    /**
     * Phase 2 시드 데이터 조회 (quizDate = null).
     * Phase 3 에서는 날짜 기반 조회로 대체된다.
     */
    @EntityGraph(attributePaths = {"choices", "article"})
    List<Quiz> findAllByQuizDateIsNullOrderByIdAsc();

    /** 특정 날짜의 퀴즈 목록 조회 (Phase 3 일별 퀴즈). */
    @EntityGraph(attributePaths = {"choices", "article"})
    List<Quiz> findAllByQuizDateOrderByIdAsc(LocalDate quizDate);

    /** 특정 날짜의 퀴즈 개수 확인. */
    long countByQuizDate(LocalDate quizDate);

    /** 특정 날짜의 퀴즈 전체 삭제 (재생성 시 사용). */
    List<Quiz> findAllByQuizDate(LocalDate quizDate);

    /**
     * 특정 날짜 이후의 퀴즈 조회 (생성 시 중복 방지 이력용).
     * 카테고리별 분류를 위해 article 을 함께 fetch 한다.
     * quizDate 가 null 인 시드 데이터는 비교 조건상 자동 제외된다.
     */
    @EntityGraph(attributePaths = {"article"})
    List<Quiz> findAllByQuizDateGreaterThanEqual(LocalDate from);

    /**
     * 여러 quiz 를 한 번에 가져오면서 choices, article 까지 함께 fetch 한다.
     * 오답노트/북마크 목록 응답 시 N+1 을 피하기 위해 사용.
     */
    @EntityGraph(attributePaths = {"choices", "article"})
    @Query("SELECT q FROM Quiz q WHERE q.id IN :ids")
    List<Quiz> findAllWithChoicesAndArticleByIdIn(@Param("ids") Collection<Long> ids);
}
