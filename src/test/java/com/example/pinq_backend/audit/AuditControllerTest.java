package com.example.pinq_backend.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import com.example.pinq_backend.audit.repository.TokenUsageRepository;
import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 검수 조회 API 의 HTTP 계층 검증.
 *
 * 가장 중요한 단언은 **정답(answer)이 실제로 실려 나오는지**다 — 이게 빠지면 치명 4종 중
 * 3종을 판정할 수 없어 검수가 빈 확인이 된다.
 */
@WebMvcTest(AuditController.class)
@Import(AuditControllerTest.FixedClockConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "admin.secret=" + AuditControllerTest.ADMIN_SECRET)
@WithMockUser
class AuditControllerTest {

    static final String ADMIN_SECRET = "audit-test-secret";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-07T01:00:00Z"), KST);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizRepository quizRepository;

    @MockitoBean
    private AuditLogBuffer auditLogBuffer;

    @MockitoBean
    private TokenUsageRepository tokenUsageRepository;

    @MockitoBean
    private QuizGenerationAttemptRepository quizGenerationAttemptRepository;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("날짜 미지정이면 오늘(KST) 발행분을 정답과 함께 반환한다")
    void returnsTodayQuizzesWithAnswer() throws Exception {
        given(quizRepository.findAllByQuizDateOrderByIdAsc(eq(LocalDate.of(2026, 8, 7))))
                .willReturn(List.of(quiz()));

        mockMvc.perform(get("/api/admin/audit/quizzes").header("X-Admin-Secret", ADMIN_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("EXCHANGE_RATE"))
                .andExpect(jsonPath("$[0].keyword").value("기준금리: 중앙은행 정책금리"))
                .andExpect(jsonPath("$[0].choices[0].answer").value(true))
                .andExpect(jsonPath("$[0].choices[1].answer").value(false));
    }

    @Test
    @DisplayName("날짜를 주면 그 날짜 발행분을 조회한다 (백필 검수)")
    void returnsQuizzesForGivenDate() throws Exception {
        given(quizRepository.findAllByQuizDateOrderByIdAsc(eq(LocalDate.of(2026, 8, 1))))
                .willReturn(List.of());

        mockMvc.perform(get("/api/admin/audit/quizzes").header("X-Admin-Secret", ADMIN_SECRET)
                        .param("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("days 는 상한으로 잘려 넘어간다")
    void clampsCountDays() throws Exception {
        given(quizRepository.findRecentPublishCounts(eq(90))).willReturn(List.of());

        mockMvc.perform(get("/api/admin/audit/counts").header("X-Admin-Secret", ADMIN_SECRET)
                        .param("days", "9999"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("logs 는 기본 30시간 창으로 조회한다")
    void logsDefaultWindow() throws Exception {
        given(auditLogBuffer.recent(eq(30), eq(null))).willReturn(List.of(
                new AuditLogBuffer.Entry(
                        LocalDateTime.of(2026, 8, 7, 6, 1),
                        "INFO", "QuizGenerationService", "퀴즈 생성 완료. 성공=5/5")));

        mockMvc.perform(get("/api/admin/audit/logs").header("X-Admin-Secret", ADMIN_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("퀴즈 생성 완료. 성공=5/5"));
    }

    @Test
    void 생성_시도_롤업을_돌려준다() throws Exception {
        given(quizGenerationAttemptRepository.rollupSince(any()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/admin/audit/generation-attempts")
                        .header("X-Admin-Secret", ADMIN_SECRET)
                        .param("days", "7"))
                .andExpect(status().isOk());
    }

    @Test
    void raw_는_그날_원시_행을_돌려준다() throws Exception {
        given(quizGenerationAttemptRepository
                .findByOccurredOnOrderByOccurredAtAsc(LocalDate.of(2026, 8, 16)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/admin/audit/generation-attempts")
                        .header("X-Admin-Secret", ADMIN_SECRET)
                        .param("date", "2026-08-16")
                        .param("raw", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void raw_날짜_형식이_어긋나면_400() throws Exception {
        mockMvc.perform(get("/api/admin/audit/generation-attempts")
                        .header("X-Admin-Secret", ADMIN_SECRET)
                        .param("date", "2026/08/16")
                        .param("raw", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("X-Admin-Secret 없이는 정답이 새지 않는다 (401)")
    void rejectsWithoutAdminSecret() throws Exception {
        mockMvc.perform(get("/api/admin/audit/quizzes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("시크릿이 틀리면 401")
    void rejectsWrongAdminSecret() throws Exception {
        mockMvc.perform(get("/api/admin/audit/quizzes").header("X-Admin-Secret", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    private static Quiz quiz() {
        return Quiz.builder()
                .category(Category.EXCHANGE_RATE)
                .quizDate(LocalDate.of(2026, 8, 7))
                .question("질문")
                .explanation("해설")
                .keyword("기준금리: 중앙은행 정책금리")
                .choices(List.of(
                        Choice.builder().orderNum(1).content("가").answer(true).build(),
                        Choice.builder().orderNum(2).content("나").answer(false).build(),
                        Choice.builder().orderNum(3).content("다").answer(false).build(),
                        Choice.builder().orderNum(4).content("라").answer(false).build()))
                .build();
    }
}
