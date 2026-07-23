package com.example.pinq_backend.user.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.dto.AttemptItemResponse;
import com.example.pinq_backend.user.dto.AttemptSummaryResponse;
import com.example.pinq_backend.user.repository.UserBookmarkRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 풀이 이력(전체/오답) 조회 유스케이스.
 *
 *  - getAllAttempts   : 사용자의 전체 풀이 이력을 최신순으로 반환 (요약)
 *  - getWrongAttempts : 사용자의 오답(첫 시도 실패) 이력만 최신순으로 반환 (요약)
 *  - getAttemptDetail : 단건 상세 — 펼친 카드용 전체 필드
 *
 * 목록은 AttemptSummaryResponse(요약), 상세는 AttemptItemResponse(전체).
 * 목록 경량화 배경·배포 순서는 docs/api/wrong-notes-lightweight-request.md 참조 —
 * 프론트 지연 로드 전환(9cfad84 계열) 배포 확인 후 목록에서 무거운 필드를 제거했다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttemptHistoryService {

    private final QuizRepository quizRepository;
    private final UserQuizAttemptRepository userQuizAttemptRepository;
    private final UserBookmarkRepository userBookmarkRepository;
    private final ReviewItemRepository reviewItemRepository;

    public List<AttemptSummaryResponse> getAllAttempts(Long userId) {
        List<UserQuizAttempt> attempts = userQuizAttemptRepository
            .findByUserIdOrderByCreatedAtDesc(userId);
        return toResponse(userId, attempts);
    }

    public List<AttemptSummaryResponse> getWrongAttempts(Long userId) {
        List<UserQuizAttempt> attempts = userQuizAttemptRepository
            .findByUserIdAndFirstCorrectFalseOrderByCreatedAtDesc(userId);
        return toResponse(userId, attempts);
    }

    /**
     * 단일 퀴즈의 오답노트/북마크 상세.
     *
     * 미풀이(attempt == null)여도 404 가 아니라, AttemptItemResponse.of 의 마스킹 규칙으로
     * 정답·해설·keyword 를 가린 채 반환한다. 존재하지 않는 quizId 만 QuizNotFoundException.
     */
    public AttemptItemResponse getAttemptDetail(Long userId, Long quizId) {
        Quiz quiz = quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(quizId))
            .stream().findFirst()
            .orElseThrow(() -> new QuizNotFoundException(quizId));
        UserQuizAttempt attempt = userQuizAttemptRepository
            .findByUserIdAndQuizId(userId, quizId).orElse(null);
        boolean bookmarked = !userBookmarkRepository
            .findBookmarkedQuizIds(userId, List.of(quizId)).isEmpty();
        ReviewItem review = reviewItemRepository
            .findAllByUserIdAndQuizIdIn(userId, List.of(quizId))
            .stream().findFirst().orElse(null);
        return AttemptItemResponse.of(quiz, attempt, bookmarked, review);
    }

    private List<AttemptSummaryResponse> toResponse(Long userId, List<UserQuizAttempt> attempts) {
        if (attempts.isEmpty()) return List.of();

        List<Long> quizIds = attempts.stream().map(UserQuizAttempt::getQuizId).toList();

        // 요약은 quiz 본문(질문·카테고리)만 필요 — choices/article fetch-join 을 걷어내
        // 응답 경량화와 함께 DB 조회도 가벼워진다 (지연 로딩 필드는 건드리지 않는다).
        Map<Long, Quiz> quizById = quizRepository
            .findAllById(quizIds)
            .stream()
            .collect(Collectors.toMap(Quiz::getId, Function.identity()));

        Set<Long> bookmarkedIds = userBookmarkRepository
            .findBookmarkedQuizIds(userId, quizIds);

        Map<Long, ReviewItem> reviewByQuizId = reviewItemRepository
            .findAllByUserIdAndQuizIdIn(userId, quizIds)
            .stream()
            .collect(Collectors.toMap(ReviewItem::getQuizId, Function.identity()));

        return attempts.stream()
            .map(att -> {
                Quiz q = quizById.get(att.getQuizId());
                if (q == null) return null;
                return AttemptSummaryResponse.of(
                    q, att, bookmarkedIds.contains(q.getId()), reviewByQuizId.get(q.getId()));
            })
            .filter(item -> item != null)
            .toList();
    }
}
