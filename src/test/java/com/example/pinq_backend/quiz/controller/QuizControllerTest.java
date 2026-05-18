package com.example.pinq_backend.quiz.controller;

import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.quiz.dto.AnswerResponse.ArticleResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse.ChoiceResponse;
import com.example.pinq_backend.quiz.exception.GlobalExceptionHandler;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.quiz.service.QuizService;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QuizController HTTP 계층 검증.
 * Phase 2 변경 사항(choice/keyword/article) 이 JSON 직렬화에 잘 반영되는지 본다.
 */
@WebMvcTest(QuizController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
@WithMockUser
class QuizControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizService quizService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        // @WithMockUser 는 principal 을 String 으로 설정하므로 SecurityUtils 가 demo 유저 경로로 진입한다.
        // getTodayQuizzes / submitAnswer 모두 getCurrentUserId 를 호출하므로 공통으로 스텁한다.
        User demoUser = mock(User.class);
        given(demoUser.getId()).willReturn(1L);
        given(userService.findDemoUser()).willReturn(demoUser);
    }

    @Test
    @DisplayName("GET /api/quizzes/today 는 200 과 퀴즈 목록을 반환한다")
    void getTodayQuizzes_returnsJson() throws Exception {
        given(quizService.getTodayQuizzes(anyLong())).willReturn(List.of(
            new QuizResponse(
                1L, "INTEREST_RATE", "금리", "금리 문제",
                List.of(
                    new ChoiceResponse(1L, 1, "보기1"),
                    new ChoiceResponse(2L, 2, "보기2"),
                    new ChoiceResponse(3L, 3, "보기3"),
                    new ChoiceResponse(4L, 4, "보기4")
                )
            )
        ));

        mockMvc.perform(get("/api/quizzes/today"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].category").value("INTEREST_RATE"))
            .andExpect(jsonPath("$[0].categoryDisplayName").value("금리"))
            .andExpect(jsonPath("$[0].question").value("금리 문제"))
            .andExpect(jsonPath("$[0].choices.length()").value(4))
            // 정답/해설/keyword/article 정보가 today 응답에 새지 않아야 함
            .andExpect(jsonPath("$[0].correctChoiceId").doesNotExist())
            .andExpect(jsonPath("$[0].explanation").doesNotExist())
            .andExpect(jsonPath("$[0].keyword").doesNotExist())
            .andExpect(jsonPath("$[0].article").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/quizzes/{id}/answer 는 채점 결과 + keyword + article 을 반환한다")
    void submitAnswer_returnsResult() throws Exception {
        given(quizService.checkAnswer(1L, 2L)).willReturn(new AnswerResponse(
            1L,
            2L,
            true,
            2L,
            "AI 해설이 여기 들어갑니다.",
            "금융통화위원회 — 한국은행 정책금리 결정 기구.",
            new ArticleResponse(
                10L, "뉴스 제목", "https://example.com/a", "더미신문",
                "INTEREST_RATE", "금리",
                LocalDateTime.of(2026, 5, 12, 9, 0)
            )
        ));

        String body = "{\"choiceId\": 2}";

        mockMvc.perform(post("/api/quizzes/1/answer")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quizId").value(1))
            .andExpect(jsonPath("$.selectedChoiceId").value(2))
            .andExpect(jsonPath("$.correct").value(true))
            .andExpect(jsonPath("$.correctChoiceId").value(2))
            .andExpect(jsonPath("$.explanation").exists())
            .andExpect(jsonPath("$.keyword").exists())
            .andExpect(jsonPath("$.article.url").value("https://example.com/a"))
            .andExpect(jsonPath("$.article.category").value("INTEREST_RATE"))
            .andExpect(jsonPath("$.article.categoryDisplayName").value("금리"));
    }

    @Test
    @DisplayName("존재하지 않는 퀴즈에 답을 제출하면 404 를 반환한다")
    void submitAnswer_quizNotFound() throws Exception {
        given(quizService.checkAnswer(999L, 1L))
            .willThrow(new QuizNotFoundException(999L));

        mockMvc.perform(post("/api/quizzes/999/answer")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choiceId\": 1}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.quizId").value(999));
    }
}
