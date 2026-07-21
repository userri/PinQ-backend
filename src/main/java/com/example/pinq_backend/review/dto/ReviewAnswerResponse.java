package com.example.pinq_backend.review.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;

/**
 * 복습 채점 결과.
 *
 * 일반 채점(AnswerResponse)과 달리 통계(스트릭/정답률)에는 반영되지 않으며,
 * 복습 주기 정보(graduated / nextDueDate)와 물 이력을 담는다.
 * article 은 일반 채점 후 화면과 동일한 기사 링크 노출용.
 *
 * @param graduated           true 면 이 문제는 복습 졸업 (더 이상 안 나옴)
 * @param nextDueDate         졸업이 아니면 다음 복습 예정일
 * @param stage               채점 반영 후 단계 (졸업이면 마지막 단계 그대로)
 * @param waterCount          이 문제에 물 준 총 횟수 (이번 시도 포함)
 * @param absorbedCount       그중 흡수된(맞힌) 횟수
 * @param totalGraduatedTrees 졸업 시에만 — 사용자의 나무 총계 (졸업 연출용). 비졸업이면 null
 */
public record ReviewAnswerResponse(
    Long quizId,
    boolean correct,
    Long correctChoiceId,
    String explanation,
    String keyword,
    AnswerResponse.ArticleResponse article,
    boolean graduated,
    LocalDate nextDueDate,
    int stage,
    int waterCount,
    int absorbedCount,
    Integer totalGraduatedTrees
) {
    public static ReviewAnswerResponse of(
        Quiz quiz,
        boolean correct,
        ReviewItem item,
        boolean graduated,
        LocalDate nextDueDate,
        Integer totalGraduatedTrees
    ) {
        return new ReviewAnswerResponse(
            quiz.getId(),
            correct,
            quiz.getAnswerChoice().getId(),
            quiz.getExplanation(),
            quiz.getKeyword(),
            AnswerResponse.ArticleResponse.from(quiz.getArticle()),
            graduated,
            nextDueDate,
            item.getStage(),
            item.getWaterCount(),
            item.getAbsorbedCount(),
            totalGraduatedTrees
        );
    }
}
