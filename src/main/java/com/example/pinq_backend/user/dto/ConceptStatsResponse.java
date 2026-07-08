package com.example.pinq_backend.user.dto;

import java.util.List;

/**
 * 카테고리(개념)별 정답률 진단.
 *
 * weakest 는 표본이 충분한(최소 시도 수 이상) 카테고리 중 정답률 최저인 것 —
 * 전부 표본 미달이면 null (진단을 왜곡하지 않기 위해).
 * 문구 생성("환율이 약해요" 등)은 클라이언트 몫으로 남긴다.
 */
public record ConceptStatsResponse(
    List<CategoryStat> categories,
    CategoryStat weakest
) {
    /**
     * @param category        Category enum name (예: EXCHANGE_RATE)
     * @param displayName     한글 표시명 (예: 환율)
     * @param total           첫 시도 수
     * @param correct         첫 시도 정답 수
     * @param correctRate     정답률 (0.0 ~ 1.0)
     */
    public record CategoryStat(
        String category,
        String displayName,
        int total,
        int correct,
        float correctRate
    ) {}
}
