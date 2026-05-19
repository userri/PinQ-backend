package com.example.pinq_backend.user.dto;

import java.util.List;

/**
 * GET /api/users/me/stats 응답.
 *
 * streak/maxStreak:
 *   - streak: 현재 연속 풀이일수 (오늘 또는 어제까지 이어진 streak. 끊기면 0).
 *   - maxStreak: 가입 이후 달성한 최고 streak. 홈/마이페이지 "최고 기록" 표시용.
 *
 * activityGrid: 오늘 포함 최근 56일(8주) 처음 시도 정답 수.
 *   index 0 = 55일 전(가장 과거), index 55 = 오늘.
 *   값 = 해당 날짜에 처음 시도에서 맞힌 문제 수 (0 = 활동 없음).
 *   강도 단계: 0(없음) / 1(1개) / 2(2개) / 3(3개) / 4(4개 이상)
 */
public record UserStatsResponse(
    String nickname,
    int streak,
    int maxStreak,
    int totalSolved,
    float correctRate,
    List<Integer> activityGrid
) {}
