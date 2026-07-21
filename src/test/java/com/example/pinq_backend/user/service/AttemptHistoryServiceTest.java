package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.dto.AttemptItemResponse;
import com.example.pinq_backend.user.repository.UserBookmarkRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.time.LocalDate;
import java.util.List;
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
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제")));
        when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(1L))).thenReturn(Set.of());
        ReviewItem item = ReviewItem.enqueue(user, 1L, LocalDate.of(2026, 7, 20));
        item.water(true);
        when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(1L)))
                .thenReturn(List.of(item));

        List<AttemptItemResponse> result = service.getWrongAttempts(USER_ID);

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
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(2L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(2L, Category.STOCK, "문제")));
        when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(2L))).thenReturn(Set.of());
        when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(2L)))
                .thenReturn(List.of());

        List<AttemptItemResponse> result = service.getAllAttempts(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).review()).isNull();
    }
}
