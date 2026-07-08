package com.example.pinq_backend.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewDailyLog;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.dto.TodayReviewsResponse;
import com.example.pinq_backend.review.repository.ReviewDailyLogRepository;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long USER_ID = 10L;

    @Mock private ReviewItemRepository reviewItemRepository;
    @Mock private ReviewDailyLogRepository reviewDailyLogRepository;
    @Mock private QuizRepository quizRepository;
    @Mock private UserRepository userRepository;

    private ReviewService service;
    private final User user = User.builder().nickname("tester").build();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        service = new ReviewService(
                reviewItemRepository, reviewDailyLogRepository, quizRepository, userRepository, clock);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(reviewItemRepository.save(any(ReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewDailyLogRepository.findByUserIdAndReviewDate(USER_ID, TODAY)).thenReturn(Optional.empty());
        when(reviewDailyLogRepository.save(any(ReviewDailyLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── enqueue ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("오답 등록: 신규면 3일 뒤 due 로 저장한다")
    void enqueue_newItem_saved() {
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.empty());

        service.enqueueWrongAnswer(USER_ID, 1L);

        verify(reviewItemRepository).save(any(ReviewItem.class));
    }

    @Test
    @DisplayName("오답 등록: 이미 큐에 있으면 기존 진행 상태를 보존한다 (재등록 안 함)")
    void enqueue_existing_skipped() {
        ReviewItem existing = ReviewItem.enqueue(user, 1L, TODAY.minusDays(5));
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L))
                .thenReturn(Optional.of(existing));

        service.enqueueWrongAnswer(USER_ID, 1L);

        verify(reviewItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("오답 등록: 동시 요청으로 유니크 제약이 터져도 예외를 삼키고 정상 종료한다")
    void enqueue_concurrentDuplicate_swallowed() {
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.empty());
        when(reviewItemRepository.save(any(ReviewItem.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_item"));

        service.enqueueWrongAnswer(USER_ID, 1L); // 예외가 새어나오면 테스트 실패
    }

    // ── getTodayReviews ──────────────────────────────────────────────────────

    @Test
    @DisplayName("오늘 복습: due 항목을 퀴즈와 함께 반환하고, 다음 예정일도 알려준다")
    void todayReviews_returnsDueWithNextDate() {
        ReviewItem due = ReviewItem.enqueue(user, 1L, TODAY.minusDays(3)); // due=TODAY
        when(reviewItemRepository.findAllByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(USER_ID, TODAY))
                .thenReturn(List.of(due));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));
        ReviewItem upcoming = ReviewItem.enqueue(user, 2L, TODAY); // due=TODAY+3
        when(reviewItemRepository.findFirstByUserIdAndDueDateAfterOrderByDueDateAsc(USER_ID, TODAY))
                .thenReturn(Optional.of(upcoming));

        TodayReviewsResponse response = service.getTodayReviews(USER_ID);

        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().get(0).quizId()).isEqualTo(1L);
        assertThat(response.reviews().get(0).question()).isEqualTo("복습 문제");
        assertThat(response.nextDueDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    @DisplayName("오늘 복습: 퀴즈가 삭제된 고아 항목은 목록에서 빼고 정리한다")
    void todayReviews_cleansOrphans() {
        ReviewItem orphan = ReviewItem.enqueue(user, 99L, TODAY.minusDays(3));
        when(reviewItemRepository.findAllByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(USER_ID, TODAY))
                .thenReturn(List.of(orphan));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(99L)))
                .thenReturn(List.of()); // 퀴즈 없음

        TodayReviewsResponse response = service.getTodayReviews(USER_ID);

        assertThat(response.reviews()).isEmpty();
        verify(reviewItemRepository).delete(orphan);
    }

    // ── answerReview ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("복습 정답(중간 단계): 다음 단계로 넘어가고 due 가 7일 뒤로 늘어난다")
    void answer_correct_advances() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(3)); // stage 0, due=TODAY
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
        when(quizRepository.findById(1L))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));

        // 픽스처 정답 choiceId=2
        ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, 2L);

        assertThat(response.correct()).isTrue();
        assertThat(response.graduated()).isFalse();
        assertThat(response.nextDueDate()).isEqualTo(TODAY.plusDays(7));
        assertThat(item.getStage()).isEqualTo(1);
        verify(reviewItemRepository, never()).delete(any());
        verify(userRepository, never()).incrementGraduatedReviewCount(anyLong()); // 아직 나무 아님
    }

    @Test
    @DisplayName("복습 정답(마지막 단계): 졸업 — 항목이 삭제되고 nextDueDate 는 null")
    void answer_correctAtMaxStage_graduates() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
        item.advanceOrGraduate(TODAY.minusDays(25)); // stage 1
        item.advanceOrGraduate(TODAY.minusDays(15)); // stage 2 (마지막)
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
        when(quizRepository.findById(1L))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));

        ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, 2L);

        assertThat(response.graduated()).isTrue();
        assertThat(response.nextDueDate()).isNull();
        verify(reviewItemRepository).delete(item);
        // 졸업 성과는 행 삭제 후에도 카운터에 남는다 — "나무 한 그루"
        verify(userRepository).incrementGraduatedReviewCount(USER_ID);
    }

    @Test
    @DisplayName("복습 오답: 3일 주기부터 다시 시작한다")
    void answer_wrong_resets() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
        item.advanceOrGraduate(TODAY.minusDays(25)); // stage 1
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
        when(quizRepository.findById(1L))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));

        ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, 3L); // 오답

        assertThat(response.correct()).isFalse();
        assertThat(response.graduated()).isFalse();
        assertThat(response.nextDueDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(item.getStage()).isZero();
        verify(userRepository, never()).incrementGraduatedReviewCount(anyLong());
    }

    // ── 일별 복습 로그 (복습만 한 날 잔디용) ──────────────────────────────────

    @Test
    @DisplayName("그날 첫 복습이면 일별 로그를 새로 만든다")
    void answer_firstReviewOfDay_createsDailyLog() {
        stubDueItemWithQuiz(1L);

        service.answerReview(USER_ID, 1L, 2L); // 정답

        ArgumentCaptor<ReviewDailyLog> captor = ArgumentCaptor.forClass(ReviewDailyLog.class);
        verify(reviewDailyLogRepository).save(captor.capture());
        assertThat(captor.getValue().getReviewDate()).isEqualTo(TODAY);
        assertThat(captor.getValue().getReviewedCount()).isEqualTo(1);
        assertThat(captor.getValue().getCorrectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 날 두 번째 복습이면 기존 로그를 증가시키고 새로 저장하지 않는다")
    void answer_secondReviewOfDay_incrementsExistingLog() {
        stubDueItemWithQuiz(1L);
        ReviewDailyLog existing = ReviewDailyLog.firstReviewOfDay(user, TODAY, true);
        when(reviewDailyLogRepository.findByUserIdAndReviewDate(USER_ID, TODAY))
                .thenReturn(Optional.of(existing));

        service.answerReview(USER_ID, 1L, 3L); // 오답

        assertThat(existing.getReviewedCount()).isEqualTo(2);
        assertThat(existing.getCorrectCount()).isEqualTo(1); // 오답이라 정답 수는 그대로
        verify(reviewDailyLogRepository, never()).save(any(ReviewDailyLog.class));
    }

    @Test
    @DisplayName("일별 로그 동시 생성으로 유니크 제약이 터지면 재조회 후 증가로 폴백한다")
    void answer_dailyLogRace_fallsBackToIncrement() {
        stubDueItemWithQuiz(1L);
        ReviewDailyLog createdByOther = ReviewDailyLog.firstReviewOfDay(user, TODAY, false);
        when(reviewDailyLogRepository.findByUserIdAndReviewDate(USER_ID, TODAY))
                .thenReturn(Optional.empty())                  // 저장 시도 전: 없음
                .thenReturn(Optional.of(createdByOther));      // 제약 위반 후 재조회: 있음
        when(reviewDailyLogRepository.save(any(ReviewDailyLog.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_daily_log"));

        service.answerReview(USER_ID, 1L, 2L); // 예외가 새어나오면 실패

        assertThat(createdByOther.getReviewedCount()).isEqualTo(2);
    }

    /** due 상태의 복습 항목 + 해당 퀴즈를 스텁한다 (픽스처 정답 choiceId=2). */
    private void stubDueItemWithQuiz(Long quizId) {
        ReviewItem item = ReviewItem.enqueue(user, quizId, TODAY.minusDays(3));
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, quizId)).thenReturn(Optional.of(item));
        when(quizRepository.findById(quizId))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(quizId, Category.STOCK, "복습 문제")));
    }

    @Test
    @DisplayName("복습 항목이 없으면 404")
    void answer_missingItem_notFound() {
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answerReview(USER_ID, 1L, 2L))
                .isInstanceOf(QuizNotFoundException.class);
    }

    @Test
    @DisplayName("퀴즈가 삭제된 고아 항목 채점 시: 항목을 정리하고 404")
    void answer_orphanItem_cleanedAnd404() {
        ReviewItem orphan = ReviewItem.enqueue(user, 99L, TODAY.minusDays(3));
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 99L)).thenReturn(Optional.of(orphan));
        when(quizRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answerReview(USER_ID, 99L, 2L))
                .isInstanceOf(QuizNotFoundException.class);
        verify(reviewItemRepository).delete(orphan);
    }

    @Test
    @DisplayName("퀴즈에 속하지 않는 choiceId 는 400 — 주기 상태를 건드리지 않는다")
    void answer_invalidChoice_rejected() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(3));
        int stageBefore = item.getStage();
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
        when(quizRepository.findById(1L))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));

        assertThatThrownBy(() -> service.answerReview(USER_ID, 1L, 999L))
                .isInstanceOf(InvalidChoiceException.class);
        assertThat(item.getStage()).isEqualTo(stageBefore);
    }
}
