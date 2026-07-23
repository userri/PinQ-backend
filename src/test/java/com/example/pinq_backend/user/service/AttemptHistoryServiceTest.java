package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.dto.AttemptItemResponse;
import com.example.pinq_backend.user.dto.AttemptSummaryResponse;
import com.example.pinq_backend.user.repository.UserBookmarkRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttemptHistoryServiceTest {

    private static final Long USER_ID = 10L;

    @Mock private QuizRepository quizRepository;
    @Mock private UserQuizAttemptRepository userQuizAttemptRepository;
    @Mock private UserBookmarkRepository userBookmarkRepository;
    @Mock private ReviewItemRepository reviewItemRepository;

    @InjectMocks private AttemptHistoryService service;

    private final User user = User.builder().nickname("tester").build();

    @Test
    @DisplayName("오답노트: 복습 큐에 있는 문제는 물 이력(review)이 붙고, 없으면 null 이다")
    void wrongNotes_includesReviewStatus() {
        UserQuizAttempt attempt = UserQuizAttempt.create(user, 1L, 3L, false);
        when(userQuizAttemptRepository.findByUserIdAndFirstCorrectFalseOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(attempt));
        // 목록은 요약이라 fetch-join 없이 findAllById 로 조회한다
        when(quizRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제")));
        when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(1L))).thenReturn(Set.of());
        ReviewItem item = ReviewItem.enqueue(user, 1L, LocalDate.of(2026, 7, 20));
        item.water(true);
        when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(1L)))
                .thenReturn(List.of(item));

        List<AttemptSummaryResponse> result = service.getWrongAttempts(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).review()).isNotNull();
        assertThat(result.get(0).review().waterCount()).isEqualTo(1);
        assertThat(result.get(0).review().absorbedCount()).isEqualTo(1);
        assertThat(result.get(0).review().graduated()).isFalse();
    }

    @Test
    @DisplayName("풀이이력: 복습 큐에 없는 문제는 review 가 null 이다")
    void attempts_noReviewStatusWhenNotQueued() {
        UserQuizAttempt attempt = UserQuizAttempt.create(user, 2L, 2L, true);
        when(userQuizAttemptRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(attempt));
        // 목록은 요약이라 fetch-join 없이 findAllById 로 조회한다
        when(quizRepository.findAllById(List.of(2L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(2L, Category.STOCK, "문제")));
        when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(2L))).thenReturn(Set.of());
        when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(2L)))
                .thenReturn(List.of());

        List<AttemptSummaryResponse> result = service.getAllAttempts(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).review()).isNull();
    }

    @Test
    @DisplayName("단건 상세: 푼 문제는 전 필드가 채워지고 solved=true 이다")
    void getAttemptDetail_solved() {
        UserQuizAttempt attempt = UserQuizAttempt.create(user, 1L, 2L, true);
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제")));
        when(userQuizAttemptRepository.findByUserIdAndQuizId(USER_ID, 1L))
                .thenReturn(Optional.of(attempt));
        when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(1L))).thenReturn(Set.of(1L));
        when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(1L)))
                .thenReturn(List.of());

        AttemptItemResponse result = service.getAttemptDetail(USER_ID, 1L);

        assertThat(result.quizId()).isEqualTo(1L);
        assertThat(result.solved()).isTrue();
        assertThat(result.correctChoiceId()).isNotNull();
        assertThat(result.explanation()).isNotNull();
        assertThat(result.keyword()).isNotNull();
        assertThat(result.bookmarked()).isTrue();
    }

    @Test
    @DisplayName("단건 상세: 미풀이 문제는 정답/해설/keyword 가 마스킹되고 solved=false 이다")
    void getAttemptDetail_notSolved() {
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제")));
        when(userQuizAttemptRepository.findByUserIdAndQuizId(USER_ID, 1L))
                .thenReturn(Optional.empty());
        when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(1L))).thenReturn(Set.of(1L));
        when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(1L)))
                .thenReturn(List.of());

        AttemptItemResponse result = service.getAttemptDetail(USER_ID, 1L);

        assertThat(result.solved()).isFalse();
        assertThat(result.correctChoiceId()).isNull();
        assertThat(result.explanation()).isNull();
        assertThat(result.keyword()).isNull();
        assertThat(result.solvedAt()).isNull();
        assertThat(result.bookmarked()).isTrue();
    }

    @Test
    @DisplayName("단건 상세: 존재하지 않는 quizId 는 QuizNotFoundException 을 던진다")
    void getAttemptDetail_quizNotFound() {
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(999L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getAttemptDetail(USER_ID, 999L))
                .isInstanceOf(QuizNotFoundException.class);
    }
}
