package com.example.pinq_backend.user.dto;

import java.util.List;

/**
 * GET /api/users/me/stats 응답.
 *
 * activityGrid: 오늘 포함 최근 56일(8주) 처음 시도 정답 수.
 *   index 0 = 55일 전(가장 과거), index 55 = 오늘.
 *   값 = 해당 날짜에 처음 시도에서 맞힌 문제 수 (0 = 활동 없음).
 *   강도 단계: 0(없음) / 1(1개) / 2(2개) / 3(3개) / 4(4개 이상)
 */
public record UserStatsResponse(
    int streak,
    int totalSolved,
    float correctRate,
    List<Integer> activityGrid
) {}
