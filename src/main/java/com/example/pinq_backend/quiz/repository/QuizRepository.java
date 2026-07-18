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

    /** 기간 내 일별 발행 문제 수 — 잔디 만점 판정(그날 발행 수 기준 목표치)에 사용. */
    @Query("SELECT q.quizDate AS quizDate, COUNT(q) AS cnt FROM Quiz q "
            + "WHERE q.quizDate BETWEEN :from AND :to GROUP BY q.quizDate")
    List<PublishedCountRow> countPublishedByDateBetween(
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface PublishedCountRow {
        LocalDate getQuizDate();
        Long getCnt();
    }

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

    /**
     * 문항별 난이도 통계 (admin 대시보드용).
     *
     * - 첫 시도만 기록되는 user_quiz_attempt 기준이라 정답률이 재풀이로 오염되지 않는다.
     * - 풀이 시간은 집계 시점에 3분(180000ms)으로 절단 — 앱 방치 이상치가 평균을 오염시키지
     *   않도록 하되, 원본은 보존한다 (임계값 변경 시 과거 데이터 재집계 가능).
     */
    @Query(value = """
        SELECT q.id AS quizId,
               q.quiz_date AS quizDate,
               na.category AS category,
               q.question AS question,
               COUNT(a.id) AS attempts,
               COALESCE(SUM(a.first_correct), 0) AS correctCount,
               ROUND(AVG(a.first_correct) * 100, 1) AS correctRate,
               ROUND(AVG(LEAST(a.first_elapsed_ms, 180000)) / 1000, 1) AS avgElapsedSec,
               COALESCE(SUM(a.feedback = 1), 0) AS upvotes,
               COALESCE(SUM(a.feedback = -1), 0) AS downvotes
        FROM quiz q
        JOIN news_article na ON q.article_id = na.id
        LEFT JOIN user_quiz_attempt a ON a.quiz_id = q.id
        WHERE q.quiz_date >= :fromDate
        GROUP BY q.id, q.quiz_date, na.category, q.question
        ORDER BY q.quiz_date DESC, na.category
        """, nativeQuery = true)
    List<QuizStatRow> findQuizStatsSince(@Param("fromDate") LocalDate fromDate);

    /** findQuizStatsSince 결과 인터페이스 프로젝션. */
    interface QuizStatRow {
        Long getQuizId();
        java.time.LocalDate getQuizDate();
        String getCategory();
        String getQuestion();
        Long getAttempts();
        Long getCorrectCount();
        Double getCorrectRate();
        Double getAvgElapsedSec();
        Long getUpvotes();
        Long getDownvotes();
    }
}
