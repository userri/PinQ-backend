package com.example.pinq_backend.user.service;

import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.exception.DuplicateNicknameException;
import com.example.pinq_backend.user.exception.UserNotFoundException;
import java.util.UUID;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import com.example.pinq_backend.user.repository.UserBookmarkRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 도메인 유스케이스.
 *
 * Phase 2: 인증 없이 nickname="demo" 단일 사용자로 운영.
 * Phase 3 (현재): OAuth 소셜 로그인 지원 추가.
 *  - loginWithOAuth() 로 카카오/구글 사용자를 upsert.
 *  - recordAnswer(userId, ...) 로 인증된 사용자의 통계를 갱신.
 *  - Phase 2 하위 호환을 위해 findDemoUser() / recordAnswer(quizId, ...) 유지.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    static final String DEMO_NICKNAME = "demo";

    private final UserRepository userRepository;
    private final SolvedHistoryRepository solvedHistoryRepository;
    private final UserQuizAttemptRepository userQuizAttemptRepository;
    private final UserBookmarkRepository userBookmarkRepository;
    private final Clock clock;

    /**
     * 퀴즈 한 문제를 채점할 때마다 호출 (Phase 2 하위 호환 — demo 유저 고정).
     * JWT 인증 없이 접근하는 경우에만 사용. 신규 코드는 recordAnswer(userId, ...) 를 쓸 것.
     */
    @Transactional
    public void recordAnswer(Long quizId, Long selectedChoiceId, boolean isCorrect) {
        User user = findOrCreateDemoUser();
        recordAnswerForUser(user, quizId, selectedChoiceId, isCorrect);
    }

    /**
     * OAuth 소셜 로그인 — (provider, oauthId) 로 사용자를 조회하거나 신규 생성한다.
     *
     * - 기존 사용자: 그대로 반환 (닉네임은 변경하지 않음)
     * - 신규 사용자: nickname 을 기반으로 고유 닉네임을 생성해 저장
     *
     * @param provider  "kakao" | "google"
     * @param oauthId   provider 에서 발급한 고유 사용자 ID
     * @param nickname  provider 프로필에서 가져온 닉네임 (신규 가입 시 사용)
     */
    @Transactional
    public User loginWithOAuth(String provider, String oauthId, String nickname) {
        return userRepository
                .findByOauthProviderAndOauthId(provider, oauthId)
                .orElseGet(() -> {
                    String uniqueNickname = makeUniqueNickname(nickname);
                    return userRepository.save(
                            User.builder()
                                    .oauthProvider(provider)
                                    .oauthId(oauthId)
                                    .nickname(uniqueNickname)
                                    .currentStreak(0)
                                    .maxStreak(0)
                                    .build()
                    );
                });
    }

    /**
     * userId 로 사용자를 조회한다.
     *
     * @throws UserNotFoundException 존재하지 않는 userId
     */
    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id=" + userId));
    }

    /**
     * 닉네임 수정 — 중복 닉네임이면 DuplicateNicknameException(409).
     *
     * @throws UserNotFoundException      존재하지 않는 userId
     * @throws DuplicateNicknameException 이미 사용 중인 닉네임
     */
    @Transactional
    public User updateNickname(Long userId, String newNickname) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id=" + userId));
        if (userRepository.findByNickname(newNickname)
                .filter(u -> !u.getId().equals(userId))
                .isPresent()) {
            throw new DuplicateNicknameException(newNickname);
        }
        try {
            user.updateNickname(newNickname);
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateNicknameException(newNickname);
        }
    }

    /**
     * userId 기반 답안 기록 — Phase 3 (인증 도입 후) 표준 메서드.
     */
    @Transactional
    public void recordAnswer(Long userId, Long quizId, Long selectedChoiceId, boolean isCorrect) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id=" + userId));
        recordAnswerForUser(user, quizId, selectedChoiceId, isCorrect);
    }

    /**
     * demo 유저를 반환한다. 존재하지 않으면 새로 생성한 뒤 반환한다.
     * Phase 2 하위 호환용 — JWT 없이 접근하는 경우에 사용.
     */
    @Transactional
    public User findDemoUser() {
        return findOrCreateDemoUser();
    }

    /**
     * 회원가입 — 닉네임으로 새 유저를 생성한다.
     * 이미 같은 닉네임이 존재하면 DuplicateNicknameException(409).
     *
     * 동시성 안전:
     *   findByNickname 체크는 빠른 경로(단일 요청 중복 방어)이며,
     *   DB 의 uk_user_nickname 이 실질적인 방어선이다.
     *   동시 요청이 둘 다 check=없음 을 보고 INSERT 하면 두 번째 요청에서
     *   DataIntegrityViolationException 이 발생하고 이를 DuplicateNicknameException 으로 변환한다.
     */
    @Transactional
    public User register(String nickname) {
        if (userRepository.findByNickname(nickname).isPresent()) {
            throw new DuplicateNicknameException(nickname);
        }
        try {
            return userRepository.saveAndFlush(
                    User.builder()
                            .nickname(nickname)
                            .currentStreak(0)
                            .maxStreak(0)
                            .build()
            );
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 같은 닉네임으로 INSERT 를 완료한 경우
            throw new DuplicateNicknameException(nickname);
        }
    }

    /**
     * 회원탈퇴 — userId 로 유저를 찾아 solved_history 포함 삭제 (Phase 3 표준).
     */
    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id=" + userId));
        deleteUser(user);
    }

    /**
     * 회원탈퇴 — 닉네임으로 유저를 찾아 삭제 (Phase 2 하위 호환).
     */
    @Transactional
    public void withdraw(String nickname) {
        User user = userRepository.findByNickname(nickname)
                .orElseThrow(() -> new UserNotFoundException(nickname));
        deleteUser(user);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 핵심 답안 기록 로직. userId/demo 어느 경로로 오든 공통으로 사용.
     *
     * 정책 — "첫 시도만 집계":
     *   같은 (user, quizId) 조합이 이미 시도된 적 있으면 통계/스트릭을 갱신하지 않는다.
     *
     * 동시성 안전:
     *   UK 제약(uk_user_quiz_attempt)이 방어선. DataIntegrityViolationException 은
     *   중복 시도로 간주하고 조용히 종료한다.
     */
    private void recordAnswerForUser(User user, Long quizId, Long selectedChoiceId, boolean isCorrect) {
        if (userQuizAttemptRepository.existsByUserIdAndQuizId(user.getId(), quizId)) {
            return;
        }

        LocalDate today = LocalDate.now(clock);

        try {
            userQuizAttemptRepository.saveAndFlush(
                    UserQuizAttempt.create(user, quizId, selectedChoiceId, isCorrect)
            );
        } catch (DataIntegrityViolationException ignored) {
            return;
        }

        user.recordSolvedOn(today);
        userRepository.save(user);

        SolvedHistory history = solvedHistoryRepository
                .findByUserIdAndSolvedDate(user.getId(), today)
                .orElseGet(() -> SolvedHistory.create(user, today));
        history.record(isCorrect);
        solvedHistoryRepository.save(history);
    }

    private void deleteUser(User user) {
        userBookmarkRepository.deleteByUserId(user.getId());
        userQuizAttemptRepository.deleteByUserId(user.getId());
        solvedHistoryRepository.deleteByUserId(user.getId());
        userRepository.delete(user);
    }

    private User findOrCreateDemoUser() {
        return userRepository.findByNickname(DEMO_NICKNAME)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .nickname(DEMO_NICKNAME)
                                .currentStreak(0)
                                .maxStreak(0)
                                .build()
                ));
    }

    /**
     * 닉네임이 이미 존재하면 뒤에 짧은 UUID suffix 를 붙여 고유하게 만든다.
     *
     * DB 컬럼 길이 30자 제한:
     *   suffix "_XXXX" = 5자 → base 는 최대 25자로 잘라야 총 30자 이하.
     * 동시 가입 경합 대비 최대 5회 재시도.
     */
    private String makeUniqueNickname(String base) {
        // suffix "_XXXX" = 5자 → base 최대 25자 (30 - 5 = 25)
        final int MAX_BASE = 25;
        String trimmed = base.length() > MAX_BASE ? base.substring(0, MAX_BASE) : base;

        if (userRepository.findByNickname(trimmed).isEmpty()) {
            return trimmed;
        }
        for (int i = 0; i < 5; i++) {
            String candidate = trimmed + "_" + UUID.randomUUID().toString().substring(0, 4);
            if (userRepository.findByNickname(candidate).isEmpty()) {
                return candidate;
            }
        }
        // 극히 드문 경우: 8자 suffix 사용 (21 + "_" + 8 = 30)
        return trimmed.substring(0, Math.min(trimmed.length(), 21))
               + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}