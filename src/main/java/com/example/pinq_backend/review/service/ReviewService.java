package com.example.pinq_backend.review.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.dto.GardenResponse;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.dto.ReviewQuizResponse;
import com.example.pinq_backend.review.dto.TodayReviewsResponse;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오답 기반 간격 반복 복습 — "잔디에 물 주기".
 *
 * 흐름:
 *  1. 일반 채점(QuizService.checkAnswer)에서 오답이면 enqueueWrongAnswer 로 등록
 *  2. GET /api/reviews/today → due 가 된 항목을 퀴즈와 함께 반환
 *  3. POST /api/reviews/{quizId}/answer → 채점 후 주기 갱신 (정답: 다음 단계/졸업, 오답: 3일 뒤 재시도)
 *
 * 복습 채점은 첫 시도 통계(스트릭·정답률)에 반영하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    /**
     * 하루 복습 큐 상한. 세션이 "완주 가능한 습관" 크기를 넘지 않게 한다 —
     * 백로그가 아무리 쌓여도 오늘 할 일은 이 개수까지만 보여주고, 나머지는 내일로.
     * (근거: 무제한 큐에서 due 55개가 쌓여 세션 자체가 돌지 않았던 2026-08 실측.)
     */
    static final int DAILY_QUEUE_CAP = 5;

    private final ReviewItemRepository reviewItemRepository;
    private final ReviewDailyLogRecorder reviewDailyLogRecorder;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 오답 발생 시 복습 큐에 등록. 이미 등록돼 있으면 그대로 둔다
     * (기존 복습 진행 상태를 재풀이가 망가뜨리지 않도록).
     *
     * REQUIRES_NEW: 채점 트랜잭션과 분리해, 동시 요청으로 유니크 제약이 터져도
     * 채점 자체(통계 기록·응답)는 영향받지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueWrongAnswer(Long userId, Long quizId) {
        if (reviewItemRepository.findByUserIdAndQuizId(userId, quizId).isPresent()) {
            return;
        }
        try {
            reviewItemRepository.save(ReviewItem.enqueue(
                    userRepository.getReferenceById(userId),
                    quizId,
                    LocalDate.now(clock)
            ));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 등록한 경우 — 이미 큐에 있으므로 정상 흐름
            log.debug("복습 항목 동시 등록 감지, 스킵. userId={}, quizId={}", userId, quizId);
        }
    }

    /**
     * 오늘 복습 세트 조회. 퀴즈가 삭제된 고아 항목은 이 시점에 정리한다.
     *
     * 밀린 항목이 아무리 쌓여도 하루치는 DAILY_QUEUE_CAP 개로 자른다 — 남은 것은
     * 사라지지 않고 내일로 넘어간다(간격 반복 규칙 자체는 그대로).
     *
     * 캡 선발분에 고아가 섞이면 그날 세트가 캡보다 작아지지만, 퀴즈 재생성 시에만
     * 생기는 드문 경우이고 다음 날 자연히 보충되므로 오버페치로 메우지 않는다.
     */
    @Transactional
    public TodayReviewsResponse getTodayReviews(Long userId) {
        LocalDate today = LocalDate.now(clock);
        List<ReviewItem> dueItems = reviewItemRepository
                .findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByStageDescDueDateAsc(
                        userId, today, Limit.of(DAILY_QUEUE_CAP));
        long dueTotal = reviewItemRepository
                .countByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqual(userId, today);

        List<ReviewQuizResponse> reviews = new ArrayList<>();
        if (!dueItems.isEmpty()) {
            List<Long> quizIds = dueItems.stream().map(ReviewItem::getQuizId).toList();
            Map<Long, Quiz> quizById = quizRepository
                    .findAllWithChoicesAndArticleByIdIn(quizIds).stream()
                    .collect(Collectors.toMap(Quiz::getId, Function.identity()));

            for (ReviewItem item : dueItems) {
                Quiz quiz = quizById.get(item.getQuizId());
                if (quiz == null) {
                    // 퀴즈가 재생성/삭제로 사라진 고아 항목 — 조용히 정리
                    log.info("복습 항목의 퀴즈가 삭제되어 정리. userId={}, quizId={}", userId, item.getQuizId());
                    reviewItemRepository.delete(item);
                    continue;
                }
                reviews.add(ReviewQuizResponse.of(item, quiz));
            }
        }

        // 캡 때문에 오늘 큐에 못 들어간 due 항목이 남아 있으면 다음 물주기는 "내일"이다.
        // 잘림 판정은 fetch 시점 개수(dueItems) 기준 — 고아 정리로 reviews 가 줄어든 것과 섞지 않는다.
        LocalDate nextDueDate = dueTotal > dueItems.size()
                ? today.plusDays(1)
                : reviewItemRepository
                        .findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(userId, today)
                        .map(ReviewItem::getDueDate)
                        .orElse(null);

        return new TodayReviewsResponse(reviews, nextDueDate);
    }

    /**
     * 정원 조회 — 자라는 항목과 졸업한 나무 전체.
     * 고아 항목(퀴즈 삭제됨)은 목록에서 제외만 한다 — 삭제 정리는 getTodayReviews 경로 담당.
     *
     * todayQueueSize 는 캡을 적용한 "오늘 물 줄 개수"다. 프론트가 growing 목록의
     * dueDate 를 직접 세면 캡 이전 숫자(밀린 전체)가 나오므로 이 값만 쓴다.
     */
    @Transactional(readOnly = true)
    public GardenResponse getGarden(Long userId) {
        LocalDate today = LocalDate.now(clock);
        List<ReviewItem> items = reviewItemRepository.findAllByUserId(userId);

        Map<Long, Quiz> quizById = items.isEmpty() ? Map.of() : quizRepository
                .findAllWithChoicesAndArticleByIdIn(items.stream().map(ReviewItem::getQuizId).toList())
                .stream()
                .collect(Collectors.toMap(Quiz::getId, Function.identity()));

        // 오늘 세트에 실제로 뽑힌 항목 — 후광 표시의 기준.
        // getTodayReviews 와 같은 선발 규칙(stage 내림차순 · 캡)을 재사용해야
        // "빛나는 개수"와 "세션에서 실제로 나오는 개수"가 어긋나지 않는다.
        Set<Long> todayQuizIds = reviewItemRepository
                .findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByStageDescDueDateAsc(
                        userId, today, Limit.of(DAILY_QUEUE_CAP))
                .stream()
                .map(ReviewItem::getQuizId)
                .collect(Collectors.toSet());

        List<GardenResponse.GardenItem> growing = new ArrayList<>();
        List<GardenResponse.GardenItem> graduated = new ArrayList<>();
        for (ReviewItem item : items) {
            Quiz quiz = quizById.get(item.getQuizId());
            if (quiz == null) continue;
            boolean graduatedItem = item.isGraduated();
            (graduatedItem ? graduated : growing).add(GardenResponse.GardenItem.of(
                    item, quiz, !graduatedItem && todayQuizIds.contains(item.getQuizId())));
        }
        growing.sort(Comparator.comparing(GardenResponse.GardenItem::dueDate));
        graduated.sort(Comparator.comparing(GardenResponse.GardenItem::graduatedAt).reversed());

        // 배지 숫자는 후광이 켜진 항목 수와 '정의상' 같아야 한다 — 같은 목록에서 센다.
        // (별도로 count 쿼리를 돌리면 고아 항목이 섞였을 때 배지 5 · 후광 4 로 어긋난다.)
        int todayQueueSize = (int) growing.stream()
                .filter(GardenResponse.GardenItem::inTodayQueue)
                .count();

        return new GardenResponse(
                growing, graduated, userRepository.findGraduatedReviewCount(userId), todayQueueSize);
    }

    /**
     * 복습 채점 + 주기 갱신.
     *
     *  - 정답: 다음 단계로 (마지막 단계였다면 졸업 — graduated_at 기록, row 보존)
     *  - 오답: stage 유지, due 만 오늘 + 3일 (진척은 되돌리지 않는다)
     *
     * due 이전의 조기 복습도 허용한다 (클라이언트는 due 항목만 노출하지만,
     * 시간대 경계·캐시 문제로 요청이 어긋나도 사용자를 막지 않는다).
     *
     * noRollbackFor QuizNotFoundException: 고아 항목(퀴즈 삭제됨)을 지운 '뒤' 404 를
     * 던지는 경로에서, 예외로 인한 롤백이 정리 작업까지 되돌리지 않게 한다.
     */
    @Transactional(noRollbackFor = QuizNotFoundException.class)
    public ReviewAnswerResponse answerReview(Long userId, Long quizId, Long selectedChoiceId) {
        ReviewItem item = reviewItemRepository.findByUserIdAndQuizId(userId, quizId)
                .orElseThrow(() -> new QuizNotFoundException(quizId));

        if (item.isGraduated()) {
            // 졸업한 나무는 영구 성취 — 어떤 경로로도 다시 복습되지 않는다 (404)
            throw new QuizNotFoundException(quizId);
        }

        Quiz quiz = quizRepository.findById(quizId)
                .orElseGet(() -> {
                    // 퀴즈가 사라진 고아 항목 — 정리 후 404
                    reviewItemRepository.delete(item);
                    throw new QuizNotFoundException(quizId);
                });

        if (!quiz.hasChoice(selectedChoiceId)) {
            throw new InvalidChoiceException(quizId, selectedChoiceId);
        }

        boolean correct = quiz.isCorrectAnswer(selectedChoiceId);
        LocalDate today = LocalDate.now(clock);

        // "물은 줬다"는 사실을 날짜 단위로 기록 — 잔디밭의 복습만 한 날 표시용.
        // REQUIRES_NEW 별도 빈에 위임: 로그 기록의 유니크 제약 경합이
        // 채점 트랜잭션(스테이지 진급·졸업 카운터)을 오염시키지 않게 한다.
        //
        // ⚠️ 반드시 아래 졸업 카운터 UPDATE '이전'에 호출해야 한다:
        // review_daily_log 의 users FK 가 users 행 S락을 요구하는데, 졸업 분기의
        // incrementGraduatedReviewCount 가 먼저 X락을 쥐면 REQUIRES_NEW 안쪽
        // 트랜잭션과 자기 교착이 된다 (QuizService.checkAnswer 의 동일 사고 참조).
        reviewDailyLogRecorder.record(userId, today, correct);

        item.water(correct); // 물 주기 — 시도 사실을 카운터에 누적 (자기 row 갱신, 락 축 무관)

        boolean graduated = false;
        Integer totalGraduatedTrees = null;
        if (correct) {
            graduated = item.advanceOrGraduate(today);
            if (graduated) {
                // 졸업 — row 는 나무 목록의 원천으로 보존하고 시각만 기록한다.
                item.graduate(LocalDateTime.now(clock));
                // 과거 졸업분(삭제된 row)을 포함한 총계는 카운터가 유일한 진실.
                // 원자적 UPDATE — 여러 기기에서 동시 졸업해도 카운트가 유실되지 않는다.
                userRepository.incrementGraduatedReviewCount(userId);
                totalGraduatedTrees = userRepository.findGraduatedReviewCount(userId);
            }
        } else {
            // 진척(stage)은 건드리지 않는다 — 다시 만날 날짜만 3일 뒤로 당긴다.
            item.retryLater(today);
        }

        return ReviewAnswerResponse.of(
                quiz, correct, item, graduated,
                graduated ? null : item.getDueDate(), totalGraduatedTrees);
    }
}
