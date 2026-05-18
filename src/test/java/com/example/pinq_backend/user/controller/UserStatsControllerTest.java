package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.quiz.exception.GlobalExceptionHandler;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.service.UserService;
import com.example.pinq_backend.user.service.UserStatsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserStatsController HTTP 계층 검증.
 *
 * 검증 대상:
 *  1. 성공 시 응답 shape (streak / totalSolved / correctRate / activityGrid 필드명·타입)
 *  2. demo 유저 미존재 시 503 반환
 *
 * activityGrid 는 Integer 값 (0~4 강도) 배열이다.
 *  - 0: 활동 없음
 *  - 1~4: 해당 날 처음 시도에서 맞힌 문제 수 (4 이상은 4로 고정)
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

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        User demoUser = mock(User.class);
        given(demoUser.getId()).willReturn(1L);
        given(userService.findDemoUser()).willReturn(demoUser);
    }

    @Test
    @DisplayName("GET /api/users/me/stats 는 200 과 통계 응답 shape 을 반환한다")
    void getMyStats_returnsJson() throws Exception {
        // activityGrid: 56개 — 앞 55일 0(활동 없음), 오늘(index 55) 3(정답 3개)
        List<Integer> grid = new ArrayList<>(Collections.nCopies(55, 0));
        grid.add(3);

        given(userStatsService.getStats(anyLong())).willReturn(
            new UserStatsResponse("테스트유저", 3, 12, 0.75f, grid)
        );

        mockMvc.perform(get("/api/users/me/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.streak").value(3))
            .andExpect(jsonPath("$.totalSolved").value(12))
            .andExpect(jsonPath("$.correctRate").value(0.75))
            .andExpect(jsonPath("$.activityGrid").isArray())
            .andExpect(jsonPath("$.activityGrid.length()").value(56))
            .andExpect(jsonPath("$.activityGrid[55]").value(3))
            .andExpect(jsonPath("$.activityGrid[0]").value(0));
    }

    @Test
    @DisplayName("demo 유저가 DB 에 없으면 503 을 반환한다")
    void getMyStats_demoUserMissing_returns503() throws Exception {
        given(userStatsService.getStats(anyLong()))
            .willThrow(new IllegalStateException("demo 유저가 존재하지 않습니다."));

        mockMvc.perform(get("/api/users/me/stats"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.error").value("Service Unavailable"))
            .andExpect(jsonPath("$.message").value("demo 유저가 존재하지 않습니다."));
    }
}
