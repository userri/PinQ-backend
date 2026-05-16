package com.example.pinq_backend.user.service;

import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.exception.DuplicateNicknameException;
import com.example.pinq_backend.user.exception.UserNotFoundException;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 도메인 유스케이스.
 *
 * Phase 2: 인증 없이 nickname="demo" 단일 사용자로 운영.
 *  - demo 유저가 DB 에 없으면 그 자리에서 생성한다(findOrCreate).
 *    DataInitializer 가 활성화되지 않는 prod 환경에서도 첫 요청에 안전하게 동작한다.
 * Phase 3: OAuth 도입 시 userId 파라미터로 교체.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    static final String DEMO_NICKNAME = "demo";

    private final UserRepository userRepository;
    private final SolvedHistoryRepository solvedHistoryRepository;
    private final Clock clock;

    /**
     * 퀴즈 한 문제를 채점할 때마다 호출.
     *  - User.currentStreak 갱신
     *  - SolvedHistory 일별 집계 upsert (명시적 save 로 dirty checking 의존 제거)
     */
    @Transactional
    public void recordAnswer(boolean isCorrect) {
        User user = userRepository.findByNickname(DEMO_NICKNAME)
            .orElseGet(() -> userRepository.save(
                User.builder()
                    .nickname(DEMO_NICKNAME)
                    .currentStreak(0)
                    .maxStreak(0)
                    .build()
            ));
        LocalDate today = LocalDate.now(clock);

        // 스트릭 갱신 (오늘 이미 기록됐으면 내부에서 무시)
        user.recordSolvedOn(today);
        userRepository.save(user);  // streak 변경 명시적 flush

        // 일별 집계 upsert — 신규 row 와 기존 row 모두 save() 로 확실히 반영
        SolvedHistory history = solvedHistoryRepository
            .findByUserIdAndSolvedDate(user.getId(), today)
            .orElseGet(() -> SolvedHistory.create(user, today));
        history.record(isCorrect);
        solvedHistoryRepository.save(history);  // INSERT or UPDATE
    }

    /**
     * demo 유저를 반환한다. 존재하지 않으면 새로 생성한 뒤 반환한다.
     */
    @Transactional
    public User findDemoUser() {
        return findOrCreateDemoUser();
    }

    /**
     * 회원가입 — 닉네임으로 새 유저를 생성한다.
     * 이미 같은 닉네임이 존재하면 DuplicateNicknameException(409).
     */
    @Transactional
    public User register(String nickname) {
        if (userRepository.findByNickname(nickname).isPresent()) {
            throw new DuplicateNicknameException(nickname);
        }
        return userRepository.save(
            User.builder()
                .nickname(nickname)
                .currentStreak(0)
                .maxStreak(0)
                .build()
        );
    }

    /**
     * 회원탈퇴 — 닉네임으로 유저를 찾아 solved_history 포함 삭제.
     * Phase 3 에서는 인증된 userId 로 교체 예정.
     */
    @Transactional
    public void withdraw(String nickname) {
        User user = userRepository.findByNickname(nickname)
            .orElseThrow(() -> new UserNotFoundException(nickname));

        // FK 제약 위반 방지: solved_history 먼저 삭제
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
}
