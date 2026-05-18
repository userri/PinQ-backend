package com.example.pinq_backend.user.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.domain.UserBookmark;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.dto.AttemptItemResponse;
import com.example.pinq_backend.user.dto.BookmarkToggleResponse;
import com.example.pinq_backend.user.repository.UserBookmarkRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 북마크 관리 유스케이스.
 *
 *  - addBookmark   : (user, quiz) 가 없으면 추가. 이미 있으면 idempotent.
 *  - removeBookmark: 있으면 삭제. 없으면 무시 (idempotent).
 *  - getBookmarks  : 사용자의 북마크 목록을 AttemptItemResponse 로 반환.
 *
 * 핵심 설계 결정:
 *  - 북마크 해제는 attempt 데이터를 건드리지 않는다.
 *  - 사용자가 풀어본 quiz 만 북마크 가능하다는 클라이언트 정책이 있지만,
 *    서버는 별도로 검증하지 않는다 (풀지 않아도 quiz 가 존재하면 북마크는 가능).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookmarkService {

    private final UserService userService;
    private final QuizRepository quizRepository;
    private final UserBookmarkRepository userBookmarkRepository;
    private final UserQuizAttemptRepository userQuizAttemptRepository;

    /** 북마크 추가 — 이미 있으면 idempotent. */
    @Transactional
    public BookmarkToggleResponse addBookmark(Long userId, Long quizId) {
        // 존재 검증 (없는 quiz 북마크 차단)
        if (!quizRepository.existsById(quizId)) {
            throw new QuizNotFoundException(quizId);
        }
        if (userBookmarkRepository.existsByUserIdAndQuizId(userId, quizId)) {
            return new BookmarkToggleResponse(quizId, true);
        }
        User user = userService.findById(userId);
        try {
            userBookmarkRepository.saveAndFlush(UserBookmark.create(user, quizId));
        } catch (DataIntegrityViolationException ignored) {
            // 동시 요청으로 이미 INSERT 된 경우 — idempotent 처리
        }
        return new BookmarkToggleResponse(quizId, true);
    }

    /** 북마크 해제 — 없으면 idempotent. */
    @Transactional
    public BookmarkToggleResponse removeBookmark(Long userId, Long quizId) {
        userBookmarkRepository.deleteByUserIdAndQuizId(userId, quizId);
        return new BookmarkToggleResponse(quizId, false);
    }

    /** 북마크 목록 조회 — 최신 북마크 순으로 정렬. */
    public List<AttemptItemResponse> getBookmarks(Long userId) {
        List<UserBookmark> bookmarks = userBookmarkRepository
            .findByUserIdOrderByCreatedAtDesc(userId);

        if (bookmarks.isEmpty()) return List.of();

        List<Long> quizIds = bookmarks.stream().map(UserBookmark::getQuizId).toList();
        Map<Long, Quiz> quizById = quizRepository
            .findAllWithChoicesAndArticleByIdIn(quizIds)
            .stream()
            .collect(Collectors.toMap(Quiz::getId, Function.identity()));

        // attempt 정보(내 답/맞춤여부) — 전체 로드 후 필터 대신 quizIds 범위만 조회
        Map<Long, UserQuizAttempt> attemptByQuizId = userQuizAttemptRepository
            .findByUserIdAndQuizIdIn(userId, quizIds)
            .stream()
            .collect(Collectors.toMap(
                UserQuizAttempt::getQuizId,
                Function.identity(),
                (a, b) -> a // 같은 (user,quiz) 는 UK 로 1개만 존재. fallback.
            ));

        return bookmarks.stream()
            .map(bm -> quizById.get(bm.getQuizId()))
            .filter(q -> q != null) // 삭제된 quiz 는 스킵
            .map(q -> AttemptItemResponse.of(q, attemptByQuizId.get(q.getId()), true))
            .toList();
    }
}
