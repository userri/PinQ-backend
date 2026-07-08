package com.example.pinq_backend.review.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.dto.ReviewQuizResponse;
import com.example.pinq_backend.review.dto.TodayReviewsResponse;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오답 기반 간격 반복 복습 — "잔디에 물 주기".
 *
 * 흐름:
 *  1. 일반 채점(QuizService.checkAnswer)에서 오답이면 enqueueWrongAnswer 로 등록
 *  2. GET /api/reviews/today → due 가 된 항목을 퀴즈와 함께 반환
 *  3. POST /api/reviews/{quizId}/answer → 채점 후 주기 갱신 (정답: 다음 단계/졸업, 오답: 리셋)
 *
 * 복습 채점은 첫 시도 통계(스트릭·정답률)에 반영하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

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

    /** 오늘 복습 세트 조회. 퀴즈가 삭제된 고아 항목은 이 시점에 정리한다. */
    @Transactional
    public TodayReviewsResponse getTodayReviews(Long userId) {
        LocalDate today = LocalDate.now(clock);
        List<ReviewItem> dueItems =
                reviewItemRepository.findAllByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(userId, today);

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

        LocalDate nextDueDate = reviewItemRepository
                .findFirstByUserIdAndDueDateAfterOrderByDueDateAsc(userId, today)
                .map(ReviewItem::getDueDate)
                .orElse(null);

        return new TodayReviewsResponse(reviews, nextDueDate);
    }

    /**
     * 복습 채점 + 주기 갱신.
     *
     *  - 정답: 다음 단계로 (마지막 단계였다면 졸업 — 항목 삭제)
     *  - 오답: stage 0, 오늘 + 3일로 리셋
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

        boolean graduated = false;
        if (correct) {
            graduated = item.advanceOrGraduate(today);
            if (graduated) {
                reviewItemRepository.delete(item);
                // 졸업 성과는 review_item 삭제 후에도 남아야 하므로 카운터에 적립한다.
                // 원자적 UPDATE — 여러 기기에서 동시 졸업해도 카운트가 유실되지 않는다.
                userRepository.incrementGraduatedReviewCount(userId);
            }
        } else {
            item.reset(today);
        }

        // "물은 줬다"는 사실을 날짜 단위로 기록 — 잔디밭의 복습만 한 날 표시용.
        // REQUIRES_NEW 별도 빈에 위임: 로그 기록의 유니크 제약 경합이
        // 채점 트랜잭션(스테이지 진급·졸업 카운터)을 오염시키지 않게 한다.
        reviewDailyLogRecorder.record(userId, today, correct);

        return ReviewAnswerResponse.of(
                quiz, correct, graduated, graduated ? null : item.getDueDate());
    }
}
