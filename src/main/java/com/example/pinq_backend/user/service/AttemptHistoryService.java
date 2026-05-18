package com.example.pinq_backend.user.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.dto.AttemptItemResponse;
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
 *  - getAllAttempts   : 사용자의 전체 풀이 이력을 최신순으로 반환
 *  - getWrongAttempts : 사용자의 오답(첫 시도 실패) 이력만 최신순으로 반환
 *
 * 응답은 BookmarkService 와 동일한 AttemptItemResponse 를 사용해 클라이언트 코드 단순화.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttemptHistoryService {

    private final QuizRepository quizRepository;
    private final UserQuizAttemptRepository userQuizAttemptRepository;
    private final UserBookmarkRepository userBookmarkRepository;

    public List<AttemptItemResponse> getAllAttempts(Long userId) {
        List<UserQuizAttempt> attempts = userQuizAttemptRepository
            .findByUserIdOrderByCreatedAtDesc(userId);
        return toResponse(userId, attempts);
    }

    public List<AttemptItemResponse> getWrongAttempts(Long userId) {
        List<UserQuizAttempt> attempts = userQuizAttemptRepository
            .findByUserIdAndFirstCorrectFalseOrderByCreatedAtDesc(userId);
        return toResponse(userId, attempts);
    }

    private List<AttemptItemResponse> toResponse(Long userId, List<UserQuizAttempt> attempts) {
        if (attempts.isEmpty()) return List.of();

        List<Long> quizIds = attempts.stream().map(UserQuizAttempt::getQuizId).toList();

        Map<Long, Quiz> quizById = quizRepository
            .findAllWithChoicesAndArticleByIdIn(quizIds)
            .stream()
            .collect(Collectors.toMap(Quiz::getId, Function.identity()));

        Set<Long> bookmarkedIds = userBookmarkRepository
            .findBookmarkedQuizIds(userId, quizIds);

        return attempts.stream()
            .map(att -> {
                Quiz q = quizById.get(att.getQuizId());
                if (q == null) return null;
                return AttemptItemResponse.of(q, att, bookmarkedIds.contains(q.getId()));
            })
            .filter(item -> item != null)
            .toList();
    }
}
