package com.example.pinq_backend.user.dto;

import java.util.List;

/**
 * GET /api/users/me/stats 응답.
 *
 * activityGrid: 오늘 포함 최근 56일(8주) 활동 여부.
 *   index 0 = 55일 전(가장 과거), index 55 = 오늘.
 *   true = 해당 날짜에 퀴즈를 한 문제 이상 풀었음.
 */
public record UserStatsResponse(
    int streak,
    int totalSolved,
    float correctRate,
    List<Boolean> activityGrid
) {}
