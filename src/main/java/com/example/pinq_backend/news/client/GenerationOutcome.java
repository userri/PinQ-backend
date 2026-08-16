package com.example.pinq_backend.news.client;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;

/**
 * 퀴즈 생성 시도 1건의 결과 — 성공이면 퀴즈, 실패면 어느 단계에서 왜 끝났는지.
 *
 * 종전 반환 타입 {@code Optional<GeneratedQuizDto>} 는 파싱 실패·룰베이스 반려·
 * 검증 실패·API 예외·LLM SKIP 다섯 가지를 전부 {@code empty()} 하나로 뭉쳤다.
 * 그래서 호출부는 "무언가 실패했다"까지만 알았고, 손실 집계는 로그 문자열을
 * 사후에 추측 분류해야 했다.
 *
 * @param quiz   성공 시 생성된 퀴즈, 실패 시 null
 * @param stage  끝난 단계 (성공이면 PUBLISHED)
 * @param reason 실패 사유 (성공이면 null)
 * @param detail 사유 원문 — 룰베이스 reason, LLM skipReason 등 (없으면 null)
 */
public record GenerationOutcome(
        GeneratedQuizDto quiz,
        AttemptStage stage,
        AttemptReason reason,
        String detail
) {

    public static GenerationOutcome success(GeneratedQuizDto quiz) {
        return new GenerationOutcome(quiz, AttemptStage.PUBLISHED, null, null);
    }

    public static GenerationOutcome failure(AttemptStage stage, AttemptReason reason,
                                            String detail) {
        return new GenerationOutcome(null, stage, reason, detail);
    }

    public boolean isSuccess() {
        return quiz != null;
    }
}
