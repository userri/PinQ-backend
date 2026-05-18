package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.config.AppConfig;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.service.UserService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * QuizService 핵심 동작을 검증한다.
 *
 *  1. 오늘의 퀴즈 목록 반환 — category 가 article 에서 파생되는지 검증
 *  2. 정답 choice 를 고르면 correct=true 와 keyword/article 포함
 *  3. 오답 choice 는 correct=false + 정답 choice id 반환
 *  4. 존재하지 않는 퀴즈 id 는 QuizNotFoundException
 *  5. 해당 퀴즈에 속하지 않는 choiceId 는 InvalidChoiceException — 통계 미기록
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private UserService userService;

    @Mock
    private Clock clock;

    @InjectMocks
    private QuizService quizService;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 14);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(TODAY.atStartOfDay(AppConfig.KST).toInstant(), AppConfig.KST);

    @BeforeEach
    void setUp() {
        // Clock.instant() / Clock.getZone() 위임 — LocalDate.now(clock) 이 TODAY 를 반환하도록
        // lenient: checkAnswer 테스트처럼 clock 을 사용하지 않는 케이스에서 UnnecessaryStubbingException 방지
        lenient().when(clock.instant()).thenReturn(FIXED_CLOCK.instant());
        lenient().when(clock.getZone()).thenReturn(FIXED_CLOCK.getZone());
    }

    @Test
    @DisplayName("오늘의 퀴즈 목록을 반환한다 — category 는 article 에서 파생")
    void getTodayQuizzes_returnsListWithCategoryFromArticle() {
        Quiz q1 = QuizFixtures.sampleQuiz(1L, Category.INTEREST_RATE, "금리 문제");
        Quiz q2 = QuizFixtures.sampleQuiz(2L, Category.EXCHANGE_RATE, "환율 문제");
        given(quizRepository.countByQuizDate(TODAY)).willReturn(0L);
        given(quizRepository.findAllByQuizDateIsNullOrderByIdAsc()).willReturn(List.of(q1, q2));

        List<QuizResponse> result = quizService.getTodayQuizzes();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).category()).isEqualTo("INTEREST_RATE");
        assertThat(result.get(0).categoryDisplayName()).isEqualTo("금리");
        assertThat(result.get(0).question()).isEqualTo("금리 문제");
        assertThat(result.get(0).choices()).hasSize(4);
        assertThat(result.get(1).question()).isEqualTo("환율 문제");
    }

    @Test
    @DisplayName("정답 choice 를 고르면 correct=true 와 keyword/article 을 반환한다")
    void checkAnswer_correct() {
        Quiz quiz = QuizFixtures.sampleQuiz(1L, Category.STOCK, "증시 문제", 2);
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        doNothing().when(userService).recordAnswer(anyLong(), any(), anyBoolean());

        AnswerResponse result = quizService.checkAnswer(1L, 2L);

        assertThat(result.quizId()).isEqualTo(1L);
        assertThat(result.selectedChoiceId()).isEqualTo(2L);
        assertThat(result.correct()).isTrue();
        assertThat(result.correctChoiceId()).isEqualTo(2L);
        assertThat(result.explanation()).isNotBlank();
        assertThat(result.keyword()).isNotBlank();
        assertThat(result.article()).isNotNull();
        assertThat(result.article().url()).startsWith("https://");
        assertThat(result.article().category()).isEqualTo("STOCK");
    }

    @Test
    @DisplayName("오답 choice 는 correct=false 와 정답 choice id 를 반환한다")
    void checkAnswer_wrong() {
        Quiz quiz = QuizFixtures.sampleQuiz(1L, Category.REAL_ESTATE, "부동산 문제", 3);
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        doNothing().when(userService).recordAnswer(anyLong(), any(), anyBoolean());

        AnswerResponse result = quizService.checkAnswer(1L, 1L);

        assertThat(result.correct()).isFalse();
        assertThat(result.selectedChoiceId()).isEqualTo(1L);
        assertThat(result.correctChoiceId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("존재하지 않는 퀴즈 id 는 QuizNotFoundException 을 던진다")
    void checkAnswer_quizNotFound() {
        given(quizRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizService.checkAnswer(999L, 1L))
                .isInstanceOf(QuizNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("해당 퀴즈에 속하지 않는 choiceId 는 InvalidChoiceException 을 던지고 통계를 기록하지 않는다")
    void checkAnswer_invalidChoiceId_throwsAndSkipsStats() {
        Quiz quiz = QuizFixtures.sampleQuiz(1L, Category.STOCK, "증시 문제", 2);
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));

        assertThatThrownBy(() -> quizService.checkAnswer(1L, 9999L))
                .isInstanceOf(InvalidChoiceException.class)
                .hasMessageContaining("9999")
                .hasMessageContaining("1");

        verify(userService, never()).recordAnswer(anyLong(), any(), anyBoolean());
    }
}