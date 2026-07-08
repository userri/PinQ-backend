package com.example.pinq_backend.user.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 연간 잔디밭 (GitHub contribution graph 스타일).
 *
 * days 는 활동이 있는 날만 담는 sparse 목록 — 빈 칸은 클라이언트가 채운다.
 * (365일 × 대부분 0 인 배열을 내려보내는 것보다 페이로드가 훨씬 작다)
 *
 * level 의미 (하루 기본 세트 = 4문제 기준):
 *   1 = 1~2문제 풀이 (연한 잔디)
 *   2 = 3문제
 *   3 = 4문제 완주
 *   4 = 4문제 완주 + 전부 정답 — "만점 잔디" (브랜드 라임으로 표시)
 *
 * @param from            잔디 시작일 (오늘 - 364일)
 * @param to              오늘
 * @param totalActiveDays 기간 내 활동일 수
 * @param perfectDays     기간 내 만점 완주일 수 (level 4)
 * @param currentStreak   현재 연속 학습일
 * @param maxStreak       최고 연속 학습일
 */
public record GrassResponse(
    LocalDate from,
    LocalDate to,
    int totalActiveDays,
    int perfectDays,
    int currentStreak,
    int maxStreak,
    List<GrassDay> days
) {
    public record GrassDay(
        LocalDate date,
        int solved,
        int correct,
        int level
    ) {}
}
