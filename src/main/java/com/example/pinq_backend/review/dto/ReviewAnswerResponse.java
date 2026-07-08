package com.example.pinq_backend.review.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import java.time.LocalDate;

/**
 * 복습 채점 결과.
 *
 * 일반 채점(AnswerResponse)과 달리 통계(스트릭/정답률)에는 반영되지 않으며,
 * 대신 복습 주기 정보(graduated / nextDueDate)를 담는다.
 *
 * @param graduated   true 면 이 문제는 복습 졸업 (더 이상 안 나옴)
 * @param nextDueDate 졸업이 아니면 다음 복습 예정일
 */
public record ReviewAnswerResponse(
    Long quizId,
    boolean correct,
    Long correctChoiceId,
    String explanation,
    String keyword,
    boolean graduated,
    LocalDate nextDueDate
) {
    public static ReviewAnswerResponse of(
        Quiz quiz,
        boolean correct,
        boolean graduated,
        LocalDate nextDueDate
    ) {
        return new ReviewAnswerResponse(
            quiz.getId(),
            correct,
            quiz.getAnswerChoice().getId(),
            quiz.getExplanation(),
            quiz.getKeyword(),
            graduated,
            nextDueDate
        );
    }
}
