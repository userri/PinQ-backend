package com.example.pinq_backend.quiz.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * axis 명명 수렴성 dry-run 결과 (스펙 2026-08-08-axis-dedup-design.md 1단계).
 *
 * wouldBlock: 이 문항의 axis 가 직전 7일 내(같은 카테고리) 이미 부여된 라벨과
 * 문자열 동일이면 true — 2단계 가드를 켰다면 폐기됐을 문항. 합계가 곧
 * "잃을 발행 수의 상한"이다 (개념 포화 충돌의 사전 측정).
 */
public record AxisLabelResponse(
        String category,
        int days,
        int labeled,
        int labelFailed,
        int wouldBlockCount,
        List<Item> items
) {
    public record Item(Long quizId, LocalDate quizDate, String term, String axis, boolean wouldBlock) {}
}
