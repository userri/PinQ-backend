package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.domain.UserBookmark;
import com.example.pinq_backend.user.dto.AttemptSummaryResponse;
import com.example.pinq_backend.user.repository.UserBookmarkRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 북마크 목록 응답 검증.
 *
 * 핵심 회귀 방지: 목록의 정렬 축(담은 시각)과 화면 표시 축이 어긋나면
 * 날짜가 뒤죽박죽으로 보인다(2026-08-04). bookmarkedAt 이 실려야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookmarkServiceTest {

    private static final Long USER_ID = 10L;

    @Mock private UserService userService;
    @Mock private QuizRepository quizRepository;
    @Mock private UserBookmarkRepository userBookmarkRepository;
    @Mock private UserQuizAttemptRepository userQuizAttemptRepository;

    @InjectMocks private BookmarkService service;

    private final User user = User.builder().nickname("tester").build();

    @Test
    @DisplayName("북마크 목록은 담은 시각(bookmarkedAt)을 함께 내려주고, 정렬 순서를 그대로 유지한다")
    void getBookmarks_carriesBookmarkedAt_inRepositoryOrder() {
        // 리포지토리는 담은 시각 최신순으로 준다 — 푼 날짜(solvedAt)와 순서가 다른 상황을 재현
        LocalDateTime recentlyBookmarked = LocalDateTime.of(2026, 8, 4, 9, 0);
        LocalDateTime earlierBookmarked = LocalDateTime.of(2026, 7, 30, 9, 0);

        UserBookmark first = bookmark(1L, recentlyBookmarked);   // 5월에 푼 문제를 어제 담음
        UserBookmark second = bookmark(2L, earlierBookmarked);

        when(userBookmarkRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(first, second));
        when(quizRepository.findAllById(any())).thenReturn(List.of(
                QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제1"),
                QuizFixtures.sampleQuiz(2L, Category.INFLATION, "문제2")
        ));
        when(userQuizAttemptRepository.findByUserIdAndQuizIdIn(anyLong(), any()))
                .thenReturn(List.of());

        List<AttemptSummaryResponse> result = service.getBookmarks(USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AttemptSummaryResponse::quizId)
                .containsExactly(1L, 2L); // 정렬 순서 보존
        assertThat(result).extracting(AttemptSummaryResponse::bookmarkedAt)
                .containsExactly(recentlyBookmarked, earlierBookmarked);
        assertThat(result).allSatisfy(item -> assertThat(item.bookmarked()).isTrue());
    }

    @Test
    @DisplayName("삭제된 퀴즈의 북마크는 건너뛴다")
    void getBookmarks_skipsDeletedQuiz() {
        when(userBookmarkRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(bookmark(1L, LocalDateTime.of(2026, 8, 4, 9, 0)),
                        bookmark(99L, LocalDateTime.of(2026, 8, 3, 9, 0))));
        when(quizRepository.findAllById(any()))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제1")));
        when(userQuizAttemptRepository.findByUserIdAndQuizIdIn(anyLong(), any()))
                .thenReturn(List.of());

        List<AttemptSummaryResponse> result = service.getBookmarks(USER_ID);

        assertThat(result).extracting(AttemptSummaryResponse::quizId).containsExactly(1L);
    }

    /** createdAt 은 @CreatedDate 라 단위 테스트에서는 리플렉션으로 채운다. */
    private UserBookmark bookmark(Long quizId, LocalDateTime createdAt) {
        UserBookmark bm = UserBookmark.create(user, quizId);
        try {
            Field f = bm.getClass().getSuperclass().getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(bm, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return bm;
    }
}
