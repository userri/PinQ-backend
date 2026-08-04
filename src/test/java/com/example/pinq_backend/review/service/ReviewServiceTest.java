package com.example.pinq_backend.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.dto.GardenResponse;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.dto.TodayReviewsResponse;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import org.springframework.data.domain.Limit;

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
        stubDueQueue(List.of(due), 1L);
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
    @DisplayName("오늘 복습: 하루 큐를 5개 상한으로 조회한다")
    void todayReviews_capsQueueAtFive() {
        stubDueQueue(List.of(), 0L);

        service.getTodayReviews(USER_ID);

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(reviewItemRepository)
                .findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByStageDescDueDateAsc(
                        eq(USER_ID), eq(TODAY), limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(5);
    }

    @Test
    @DisplayName("오늘 복습: 캡에 잘린 백로그가 남아 있으면 다음 물주기는 내일이다")
    void todayReviews_truncated_nextDueIsTomorrow() {
        List<ReviewItem> capped = new ArrayList<>();
        List<Quiz> quizzes = new ArrayList<>();
        for (long quizId = 1; quizId <= 5; quizId++) {
            capped.add(ReviewItem.enqueue(user, quizId, TODAY.minusDays(3)));
            quizzes.add(QuizFixtures.sampleQuiz(quizId, Category.STOCK, "복습 문제 " + quizId));
        }
        stubDueQueue(capped, 55L); // due 55개 중 5개만 선발됨
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L, 2L, 3L, 4L, 5L)))
                .thenReturn(quizzes);

        TodayReviewsResponse response = service.getTodayReviews(USER_ID);

        assertThat(response.reviews()).hasSize(5);
        assertThat(response.nextDueDate()).isEqualTo(TODAY.plusDays(1));
        // 잘렸으면 미래 예정일 조회는 필요 없다
        verify(reviewItemRepository, never())
                .findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(anyLong(), any());
    }

    @Test
    @DisplayName("오늘 복습: 퀴즈가 삭제된 고아 항목은 목록에서 빼고 정리한다")
    void todayReviews_cleansOrphans() {
        ReviewItem orphan = ReviewItem.enqueue(user, 99L, TODAY.minusDays(3));
        stubDueQueue(List.of(orphan), 1L);
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(99L)))
                .thenReturn(List.of()); // 퀴즈 없음

        TodayReviewsResponse response = service.getTodayReviews(USER_ID);

        assertThat(response.reviews()).isEmpty();
        verify(reviewItemRepository).delete(orphan);
    }

    /** 오늘 큐 선발 결과와 due 총계를 스텁한다. */
    private void stubDueQueue(List<ReviewItem> selected, long dueTotal) {
        when(reviewItemRepository
                .findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByStageDescDueDateAsc(
                        eq(USER_ID), eq(TODAY), any(Limit.class)))
                .thenReturn(selected);
        when(reviewItemRepository.countByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqual(USER_ID, TODAY))
                .thenReturn(dueTotal);
    }

    // ── getGarden ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정원: 자라는 항목은 due 오름차순, 나무는 졸업 최신순으로 나눠 반환한다")
    void garden_splitsGrowingAndGraduated() {
        ReviewItem growing = ReviewItem.enqueue(user, 1L, TODAY.minusDays(1)); // due=TODAY+2
        ReviewItem tree = ReviewItem.enqueue(user, 2L, TODAY.minusDays(30));
        tree.water(true);
        tree.graduate(TODAY.minusDays(1).atStartOfDay());
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(growing, tree));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L, 2L))).thenReturn(List.of(
                QuizFixtures.sampleQuiz(1L, Category.STOCK, "자라는 문제"),
                QuizFixtures.sampleQuiz(2L, Category.STOCK, "나무 문제")));
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(3);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.growing()).hasSize(1);
        assertThat(response.growing().get(0).quizId()).isEqualTo(1L);
        assertThat(response.graduated()).hasSize(1);
        assertThat(response.graduated().get(0).waterCount()).isEqualTo(1);
        assertThat(response.graduatedTrees()).isEqualTo(3); // 카운터 값 — 목록 길이와 다를 수 있음
    }

    @Test
    @DisplayName("정원: 밀린 항목이 7개여도 오늘 세트로 뽑힌 5개만 inTodayQueue=true (후광 대상)")
    void garden_inTodayQueue_onlySelectedItems() {
        // due 7개 — 전부 dueDate <= TODAY 라 'dueDate 만 보면' 7개가 후광 대상이 된다
        List<ReviewItem> dueItems = new java.util.ArrayList<>();
        for (long id = 1; id <= 7; id++) {
            dueItems.add(ReviewItem.enqueue(user, id, TODAY.minusDays(3 + id)));
        }
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(dueItems);
        // 서버 선발 결과는 상위 5개
        when(reviewItemRepository
                .findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByStageDescDueDateAsc(
                        eq(USER_ID), eq(TODAY), any()))
                .thenReturn(dueItems.subList(0, 5));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(anyList())).thenReturn(
                dueItems.stream()
                        .map(i -> QuizFixtures.sampleQuiz(i.getQuizId(), Category.STOCK, "문제" + i.getQuizId()))
                        .toList());
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(0);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.growing()).hasSize(7);
        assertThat(response.growing().stream().filter(GardenResponse.GardenItem::inTodayQueue))
                .hasSize(5);
        // 배지와 후광 개수는 정의상 같다 — 화면에서 "빛나는 건 7개인데 배지는 5" 가 나올 수 없다
        assertThat(response.todayQueueSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("정원: 오늘 세트가 비면 후광도 배지도 0")
    void garden_inTodayQueue_emptyWhenNothingDue() {
        ReviewItem notDue = ReviewItem.enqueue(user, 1L, TODAY); // due = TODAY+3
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(notDue));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "아직 아님")));
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(0);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.growing().get(0).inTodayQueue()).isFalse();
        assertThat(response.todayQueueSize()).isZero();
    }

    @Test
    @DisplayName("정원: 졸업한 나무는 후광 대상이 아니다")
    void garden_graduated_neverInTodayQueue() {
        ReviewItem tree = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
        tree.graduate(TODAY.minusDays(1).atStartOfDay());
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(tree));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
                .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "나무")));
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(1);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.graduated().get(0).inTodayQueue()).isFalse();
        assertThat(response.todayQueueSize()).isZero();
    }

    @Test
    @DisplayName("정원: 퀴즈가 삭제된 고아 항목은 목록에서 제외한다 (정리는 today 경로가 담당)")
    void garden_skipsOrphans() {
        ReviewItem orphan = ReviewItem.enqueue(user, 9L, TODAY);
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(orphan));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(9L))).thenReturn(List.of());
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(0);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.growing()).isEmpty();
        assertThat(response.graduated()).isEmpty();
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
