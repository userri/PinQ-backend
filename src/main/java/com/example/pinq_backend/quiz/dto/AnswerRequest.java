package com.example.pinq_backend.quiz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 정답 제출 요청.
 * choiceId: 사용자가 선택한 Choice 의 id.
 */
public record AnswerRequest(
    @NotNull(message = "choiceId 는 필수입니다.")
    @Positive(message = "choiceId 는 양수여야 합니다.")
    Long choiceId
) {
}
