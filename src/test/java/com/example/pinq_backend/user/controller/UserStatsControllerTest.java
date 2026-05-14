package com.example.pinq_backend.user.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pinq_backend.quiz.exception.GlobalExceptionHandler;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.service.UserStatsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UserStatsController HTTP 계층 검증.
 *
 * 검증 대상:
 *  1. 성공 시 응답 shape (streak / totalSolved / correctRate / activityGrid 필드명·타입)
 *  2. demo 유저 미존재 시 503 반환
 *
 * 피드백 반영 범위 메모:
 *  - "demo user missing → 에러 경로" 테스트를 추가하려면 GlobalExceptionHandler 가
 *    IllegalStateException 을 먼저 처리해야 한다. 핸들러 미등록 상태에서 테스트만 추가하면
 *    500 을 검증하는 무의미한 테스트가 되므로, GlobalExceptionHandler 수정을 선행한 뒤
 *    이 테스트를 작성했다.
 */
@WebMvcTest(UserStatsController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
@WithMockUser
class UserStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserStatsService userStatsService;

    @Test
    @DisplayName("GET /api/users/me/stats 는 200 과 통계 응답 shape 을 반환한다")
    void getMyStats_returnsJson() throws Exception {
        // activityGrid: 56개 — 앞 55일 false, 오늘(index 55) true
        List<Boolean> grid = new ArrayList<>(Collections.nCopies(55, false));
        grid.add(true);

        given(userStatsService.getStats()).willReturn(
            new UserStatsResponse(3, 12, 0.75f, grid)
        );

        mockMvc.perform(get("/api/users/me/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.streak").value(3))
            .andExpect(jsonPath("$.totalSolved").value(12))
            .andExpect(jsonPath("$.correctRate").value(0.75))
            .andExpect(jsonPath("$.activityGrid").isArray())
            .andExpect(jsonPath("$.activityGrid.length()").value(56))
            .andExpect(jsonPath("$.activityGrid[55]").value(true))
            .andExpect(jsonPath("$.activityGrid[0]").value(false));
    }

    @Test
    @DisplayName("demo 유저가 DB 에 없으면 503 을 반환한다")
    void getMyStats_demoUserMissing_returns503() throws Exception {
        given(userStatsService.getStats())
            .willThrow(new IllegalStateException("demo 유저가 존재하지 않습니다."));

        mockMvc.perform(get("/api/users/me/stats"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.error").value("Service Unavailable"))
            .andExpect(jsonPath("$.message").value("demo 유저가 존재하지 않습니다."));
    }
}
