package com.example.pinq_backend.quiz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 정답 제출 요청.
 * choiceId: 사용자가 선택한 Choice 의 id.
 * elapsedMs: (선택) 풀이 소요 시간 ms — 클라이언트가 포그라운드 시간만 측정해 전송.
 *            난이도 측정용 암묵 신호로, 미전송(구버전 클라이언트)이어도 채점은 동일하다.
 */
public record AnswerRequest(
    @NotNull(message = "choiceId 는 필수입니다.")
    @Positive(message = "choiceId 는 양수여야 합니다.")
    Long choiceId,

    @Positive(message = "elapsedMs 는 양수여야 합니다.")
    Integer elapsedMs
) {
}
