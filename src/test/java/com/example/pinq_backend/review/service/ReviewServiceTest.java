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
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.dto.TodayReviewsResponse;
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
    @Mock private ReviewDailyLogRecorder reviewDailyLogRecorder;
    @Mock private QuizRepository quizRepository;
    @Mock private UserRepository userRepository;

    private ReviewService service;
    private final User user = User.builder().nickname("tester").build();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        service = new ReviewService(
                reviewItemRepository, reviewDailyLogRecorder, quizRepository, userRepository, clock);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(reviewItemRepository.save(any(ReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));
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
        when(reviewItemRepository.findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(USER_ID, TODAY))
                .thenReturn(List.of(due));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));
        ReviewItem upcoming = ReviewItem.enqueue(user, 2L, TODAY); // due=TODAY+3
        when(reviewItemRepository.findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(USER_ID, TODAY))
                .thenReturn(Optional.of(upcoming));

        TodayReviewsResponse response = service.getTodayReviews(USER_ID);

        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().get(0).quizId()).isEqualTo(1L);
        assertThat(response.reviews().get(0).question()).isEqualTo("복습 문제");
        assertThat(response.reviews().get(0).waterCount()).isEqualTo(0);
        assertThat(response.reviews().get(0).absorbedCount()).isEqualTo(0);
        assertThat(response.nextDueDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    @DisplayName("오늘 복습: 퀴즈가 삭제된 고아 항목은 목록에서 빼고 정리한다")
    void todayReviews_cleansOrphans() {
        ReviewItem orphan = ReviewItem.enqueue(user, 99L, TODAY.minusDays(3));
        when(reviewItemRepository.findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(USER_ID, TODAY))
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
    @DisplayName("복습 정답(마지막 단계): 졸업 — row 는 보존(graduatedAt 기록)되고 나무 총계를 돌려준다")
    void answer_correctAtMaxStage_graduates() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
        item.advanceOrGraduate(TODAY.minusDays(25)); // stage 1
        item.advanceOrGraduate(TODAY.minusDays(15)); // stage 2 (마지막)
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
        when(quizRepository.findById(1L))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(4);

        ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, 2L);

        assertThat(response.graduated()).isTrue();
        assertThat(response.nextDueDate()).isNull();
        assertThat(response.totalGraduatedTrees()).isEqualTo(4);
        assertThat(item.isGraduated()).isTrue();
        verify(reviewItemRepository, never()).delete(any());
        // 졸업 성과는 카운터에도 적립된다 — "나무 한 그루"
        verify(userRepository).incrementGraduatedReviewCount(USER_ID);
    }

    @Test
    @DisplayName("복습 채점: 시도마다 물 카운터가 오르고(정답이면 흡수도), 응답에 기사가 실린다")
    void answer_watersItem() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(3));
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
        when(quizRepository.findById(1L))
                .thenReturn(Optional.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "복습 문제")));

        ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, 2L); // 정답

        assertThat(item.getWaterCount()).isEqualTo(1);
        assertThat(item.getAbsorbedCount()).isEqualTo(1);
        assertThat(response.waterCount()).isEqualTo(1);
        assertThat(response.absorbedCount()).isEqualTo(1);
        assertThat(response.totalGraduatedTrees()).isNull(); // 비졸업이면 총계 없음
        assertThat(response.article()).isNotNull(); // 일반 채점 화면과 동일하게 기사 노출
    }

    @Test
    @DisplayName("복습 채점: 이미 졸업한 항목은 404 — 다시 복습할 수 없다")
    void answer_graduatedItem_notFound() {
        ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
        item.graduate(TODAY.minusDays(1).atStartOfDay());
        when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.answerReview(USER_ID, 1L, 2L))
                .isInstanceOf(QuizNotFoundException.class);
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

    // ── 일별 복습 로그 위임 (upsert 자체는 ReviewDailyLogRecorderTest 에서 검증) ──

    @Test
    @DisplayName("복습 채점마다 일별 로그 기록기를 정답 여부와 함께 호출한다")
    void answer_recordsDailyLog() {
        stubDueItemWithQuiz(1L);

        service.answerReview(USER_ID, 1L, 2L); // 정답

        verify(reviewDailyLogRecorder).record(USER_ID, TODAY, true);
    }

    @Test
    @DisplayName("복습 오답도 일별 로그에 기록된다 (물은 줬다)")
    void answer_wrong_alsoRecordsDailyLog() {
        stubDueItemWithQuiz(1L);

        service.answerReview(USER_ID, 1L, 3L); // 오답

        verify(reviewDailyLogRecorder).record(USER_ID, TODAY, false);
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
