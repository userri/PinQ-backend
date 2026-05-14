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
     *  - SolvedHistory 일별 집계 upsert
     */
    @Transactional
    public void recordAnswer(boolean isCorrect) {
        User user = findOrCreateDemoUser();
        LocalDate today = LocalDate.now(clock);

        // 스트릭 갱신 (오늘 이미 기록됐으면 내부에서 무시)
        user.recordSolvedOn(today);

        // 일별 집계 upsert
        SolvedHistory history = solvedHistoryRepository
            .findByUserIdAndSolvedDate(user.getId(), today)
            .orElseGet(() -> solvedHistoryRepository.save(SolvedHistory.create(user, today)));
        history.record(isCorrect);
    }

    /**
     * demo 유저를 반환한다. 존재하지 않으면 새로 생성한 뒤 반환한다.
     *
     * prod 프로파일에서는 DataInitializer 가 실행되지 않아 demo 유저가 없을 수 있다.
     * orElseGet 으로 생성하므로 어떤 프로파일·환경에서도 503 이 발생하지 않는다.
     */
    @Transactional
    public User findDemoUser() {
        return findOrCreateDemoUser();
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
