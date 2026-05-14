package com.example.pinq_backend.user.service;

import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
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
 * Phase 3: OAuth 도입 시 userId 파라미터로 교체.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String DEMO_NICKNAME = "demo";

    private final UserRepository userRepository;
    private final SolvedHistoryRepository solvedHistoryRepository;
    private final Clock clock;

    /**
     * 퀴즈 한 문제를 채점할 때마다 호출.
     *  - User.currentStreak 갱신
     *  - SolvedHistory 일별 집계 upsert
     */
    @Transactional
    public void recordAnswer(boolean isCorrect) {
        User user = findDemoUser();
        LocalDate today = LocalDate.now(clock);

        // 스트릭 갱신 (오늘 이미 기록됐으면 내부에서 무시)
        user.recordSolvedOn(today);

        // 일별 집계 upsert
        SolvedHistory history = solvedHistoryRepository
            .findByUserIdAndSolvedDate(user.getId(), today)
            .orElseGet(() -> solvedHistoryRepository.save(SolvedHistory.create(user, today)));
        history.record(isCorrect);
    }

    /** demo 유저 조회 — 없으면 예외. */
    public User findDemoUser() {
        return userRepository.findByNickname(DEMO_NICKNAME)
            .orElseThrow(() -> new IllegalStateException("demo 유저가 존재하지 않습니다."));
    }
}
