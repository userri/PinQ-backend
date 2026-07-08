package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.auth.SecurityUtils;
import com.example.pinq_backend.user.dto.GrassResponse;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.service.UserService;
import com.example.pinq_backend.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 통계 REST API.
 *
 *  GET /api/users/me/stats : 스트릭 · 누적 풀이 · 정답률 · 활동 그리드 반환
 *  GET /api/users/me/grass : 연간 잔디밭 (최근 365일, 활동일만 sparse)
 *
 * JWT 가 있으면 해당 유저의 통계를, 없으면 demo 유저 통계를 반환한다.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserStatsController {

    private final UserStatsService userStatsService;
    private final  UserService userService;

    @GetMapping("/me/stats")
    public UserStatsResponse getMyStats() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return userStatsService.getStats(userId);
    }

    /** 연간 잔디밭 — "경제잔디" 브랜드 코어 화면용 데이터. */
    @GetMapping("/me/grass")
    public GrassResponse getMyGrass() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return userStatsService.getGrass(userId);
    }
}
