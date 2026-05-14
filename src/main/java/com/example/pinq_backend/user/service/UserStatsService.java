package com.example.pinq_backend.user.service;

import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private final UserService userService;
    private final SolvedHistoryRepository solvedHistoryRepository;

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
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(GRID_DAYS - 1);

        List<SolvedHistory> recent =
            solvedHistoryRepository.findByUserIdAndSolvedDateBetween(userId, from, today);

        Set<LocalDate> activeDates = recent.stream()
            .filter(h -> h.getSolvedCount() > 0)
            .map(SolvedHistory::getSolvedDate)
            .collect(Collectors.toSet());

        // index 0 = 가장 과거(55일 전), index 55 = 오늘
        List<Boolean> activityGrid = new ArrayList<>(GRID_DAYS);
        for (int i = GRID_DAYS - 1; i >= 0; i--) {
            activityGrid.add(activeDates.contains(today.minusDays(i)));
        }

        return new UserStatsResponse(streak, totalSolved, correctRate, activityGrid);
    }
}
