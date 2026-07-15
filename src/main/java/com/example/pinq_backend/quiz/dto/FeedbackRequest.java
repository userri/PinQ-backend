package com.example.pinq_backend.quiz.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 문제 피드백 요청 — 해설 화면의 선택적 1탭.
 * value: 1(좋아요) 또는 -1(별로예요). 중립(0)은 "미응답 = NULL"로 표현하므로 받지 않는다.
 */
public record FeedbackRequest(
    @NotNull(message = "value 는 필수입니다.")
    @Min(value = -1, message = "value 는 1 또는 -1 이어야 합니다.")
    @Max(value = 1, message = "value 는 1 또는 -1 이어야 합니다.")
    Integer value
) {
    public FeedbackRequest {
        if (value != null && value == 0) {
            throw new IllegalArgumentException("value 는 1 또는 -1 이어야 합니다.");
        }
    }
}
