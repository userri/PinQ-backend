package com.example.pinq_backend.user.service;

import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserStatsService {

    /** activityGrid 기간: 8주 × 7일 = 56일. */
    private static final int GRID_DAYS = 56;

    /** 강도 최대값 — 4개 이상은 모두 4단계로 표시. */
    private static final int MAX_INTENSITY = 4;

    private final UserService userService;
    private final SolvedHistoryRepository solvedHistoryRepository;
    private final UserQuizAttemptRepository userQuizAttemptRepository;
    private final Clock clock;

    /** Phase 3 표준: 인증된 userId 로 통계를 조회한다. */
    public UserStatsResponse getStats(Long userId) {
        User user = userService.findById(userId);
        return buildStats(user);
    }

    /** Phase 2 하위 호환: demo 유저 통계를 반환한다. */
    public UserStatsResponse getStats() {
        User user = userService.findDemoUser();
        return buildStats(user);
    }

    private UserStatsResponse buildStats(User user) {
        Long userId = user.getId();

        // ── 스트릭 ────────────────────────────────────────────────
        int streak = user.getCurrentStreak();

        // ── 누적 풀이 / 정답률 ────────────────────────────────────
        List<SolvedHistory> allHistory = solvedHistoryRepository.findByUserId(userId);
        int totalSolved  = allHistory.stream().mapToInt(SolvedHistory::getSolvedCount).sum();
        int totalCorrect = allHistory.stream().mapToInt(SolvedHistory::getCorrectCount).sum();
        float correctRate = totalSolved > 0 ? (float) totalCorrect / totalSolved : 0f;

        // ── activityGrid (최근 56일) ──────────────────────────────
        // 강도 의미:
        //   0 = 활동 없음 (시도조차 안 한 날)
        //   1 = 활동은 했으나 맞힌 개수 0개
        //   2 = 1개 정답
        //   3 = 2개 정답
        //   4 = 3개 이상 정답 (최대 강도)
        LocalDate today = LocalDate.now(clock);
        LocalDate from  = today.minusDays(GRID_DAYS - 1);

        // 날짜 → 첫 시도 수 맵 (정답 여부 무관)
        // native query 반환 타입: DATE → java.sql.Date, COUNT → Long|BigInteger (DB 방언 차이)
        Map<LocalDate, Integer> attemptsByDate =
            userQuizAttemptRepository
                .countAttemptsByDateBetween(userId, from, today)
                .stream()
                .collect(Collectors.toMap(
                    row -> toLocalDate(row[0]),
                    row -> ((Number) row[1]).intValue()
                ));

        // 날짜 → 첫 시도 정답 수 맵
        Map<LocalDate, Integer> correctByDate =
            userQuizAttemptRepository
                .countFirstCorrectByDateBetween(userId, from, today)
                .stream()
                .collect(Collectors.toMap(
                    row -> toLocalDate(row[0]),
                    row -> ((Number) row[1]).intValue()
                ));

        // index 0 = 가장 과거(55일 전), index 55 = 오늘
        List<Integer> activityGrid = new ArrayList<>(GRID_DAYS);
        for (int i = GRID_DAYS - 1; i >= 0; i--) {
            LocalDate date    = today.minusDays(i);
            int attempts = attemptsByDate.getOrDefault(date, 0);
            int correct  = correctByDate.getOrDefault(date, 0);

            int intensity;
            if (attempts == 0) {
                intensity = 0;                               // 활동 없음
            } else if (correct == 0) {
                intensity = 1;                               // 활동은 했으나 정답 0개
            } else {
                intensity = Math.min(correct + 1, MAX_INTENSITY); // 1개→2, 2개→3, 3+개→4
            }
            activityGrid.add(intensity);
        }

        return new UserStatsResponse(user.getNickname(), streak, totalSolved, correctRate, activityGrid);
    }

    /**
     * native query 날짜 컬럼을 LocalDate 로 안전하게 변환한다.
     * DB/드라이버에 따라 java.sql.Date 또는 LocalDate 가 내려올 수 있다.
     */
    private static LocalDate toLocalDate(Object obj) {
        if (obj instanceof LocalDate ld) return ld;
        if (obj instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(obj.toString()); // fallback
    }
}

