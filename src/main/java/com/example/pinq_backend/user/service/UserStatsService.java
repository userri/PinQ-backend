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

    public UserStatsResponse getStats() {
        User user = userService.findDemoUser();
        Long userId = user.getId();

        // ── 스트릭 ────────────────────────────────────────────────
        int streak = user.getCurrentStreak();

        // ── 누적 풀이 / 정답률 ────────────────────────────────────
        List<SolvedHistory> allHistory = solvedHistoryRepository.findByUserId(userId);
        int totalSolved  = allHistory.stream().mapToInt(SolvedHistory::getSolvedCount).sum();
        int totalCorrect = allHistory.stream().mapToInt(SolvedHistory::getCorrectCount).sum();
        float correctRate = totalSolved > 0 ? (float) totalCorrect / totalSolved : 0f;

        // ── activityGrid (최근 56일) ──────────────────────────────
        // 날짜별 처음 시도 정답 수를 집계하여 강도(0~4)로 변환한다.
        LocalDate today = LocalDate.now(clock);
        LocalDate from  = today.minusDays(GRID_DAYS - 1);

        // 날짜 → 첫 시도 정답 수 맵
        Map<LocalDate, Integer> correctByDate =
            userQuizAttemptRepository
                .countFirstCorrectByDateBetween(userId, from, today)
                .stream()
                .collect(Collectors.toMap(
                    row -> (LocalDate) row[0],
                    row -> ((Long) row[1]).intValue()
                ));

        // index 0 = 가장 과거(55일 전), index 55 = 오늘
        List<Integer> activityGrid = new ArrayList<>(GRID_DAYS);
        for (int i = GRID_DAYS - 1; i >= 0; i--) {
            int count = correctByDate.getOrDefault(today.minusDays(i), 0);
            activityGrid.add(Math.min(count, MAX_INTENSITY));
        }

        return new UserStatsResponse(streak, totalSolved, correctRate, activityGrid);
    }
}
