package com.example.pinq_backend.review.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.dto.QuizResponse.ChoiceResponse;
import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.util.List;

/**
 * 복습 화면용 퀴즈 항목.
 *
 * QuizResponse 와 달리 solved/correct 가 없고(복습은 항상 재풀이),
 * 복습 메타(stage, dueDate, 물 이력)가 붙는다. 정답/해설은 채점 후에만 노출.
 */
public record ReviewQuizResponse(
    Long quizId,
    String category,
    String categoryDisplayName,
    String question,
    List<ChoiceResponse> choices,
    int stage,
    LocalDate dueDate,
    int waterCount,
    int absorbedCount
) {
    public static ReviewQuizResponse of(ReviewItem item, Quiz quiz) {
        return new ReviewQuizResponse(
            quiz.getId(),
            quiz.getCategory().name(),
            quiz.getCategory().getDisplayName(),
            quiz.getQuestion(),
            quiz.getChoices().stream()
                .map(c -> new ChoiceResponse(c.getId(), c.getOrderNum(), c.getContent()))
                .toList(),
            item.getStage(),
            item.getDueDate(),
            item.getWaterCount(),
            item.getAbsorbedCount()
        );
    }
}
