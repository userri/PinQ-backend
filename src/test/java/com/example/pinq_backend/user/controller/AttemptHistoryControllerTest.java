package com.example.pinq_backend.user.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.quiz.exception.GlobalExceptionHandler;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.service.AttemptHistoryService;
import com.example.pinq_backend.user.service.UserService;
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

/**
 * AttemptHistoryController HTTP 계층 검증.
 *
 *  1. GET /api/me/attempts    : 전체 풀이 이력 200
 *  2. GET /api/me/wrong-notes : 오답노트 200
 */
@WebMvcTest(AttemptHistoryController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
@WithMockUser
class AttemptHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttemptHistoryService attemptHistoryService;

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
    @DisplayName("GET /api/me/attempts 는 200 과 배열을 반환한다")
    void getAttempts_returnsOk() throws Exception {
        given(attemptHistoryService.getAllAttempts(anyLong())).willReturn(List.of());

        mockMvc.perform(get("/api/me/attempts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/me/wrong-notes 는 200 과 배열을 반환한다")
    void getWrongNotes_returnsOk() throws Exception {
        given(attemptHistoryService.getWrongAttempts(anyLong())).willReturn(List.of());

        mockMvc.perform(get("/api/me/wrong-notes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }
}
