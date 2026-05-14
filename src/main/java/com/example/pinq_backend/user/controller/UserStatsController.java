package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 통계 REST API.
 *
 *  GET /api/users/me/stats : 스트릭 · 누적 풀이 · 정답률 · 활동 그리드 반환
 *
 * Phase 2: 인증 없이 demo 유저 고정.
 * Phase 3: Authorization 헤더로 실제 유저 식별 예정.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserStatsController {

    private final UserStatsService userStatsService;

    @GetMapping("/me/stats")
    public UserStatsResponse getMyStats() {
        return userStatsService.getStats();
    }
}
