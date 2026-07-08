package com.example.pinq_backend.user.service;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.review.domain.ReviewDailyLog;
import com.example.pinq_backend.review.repository.ReviewDailyLogRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.ConceptStatsResponse;
import com.example.pinq_backend.user.dto.GrassResponse;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** 강도 최대값 — 4개 이상은 모두 4단계로 표시. */
    private static final int MAX_INTENSITY = 4;

    private final UserService userService;
    private final UserQuizAttemptRepository userQuizAttemptRepository;
    private final ReviewDailyLogRepository reviewDailyLogRepository;
    private final Clock clock;

    /** Phase 3 표준: 인증된 userId 로 통계를 조회한다. */
    @Transactional
    public UserStatsResponse getStats(Long userId) {
        User user = userService.synchronizeStreak(userId);
        return buildStats(user);
    }

    /** Phase 2 하위 호환: demo 유저 통계를 반환한다. */
    @Transactional
    public UserStatsResponse getStats() {
        User user = userService.synchronizeDemoStreak();
        return buildStats(user);
    }

    private UserStatsResponse buildStats(User user) {
        Long userId = user.getId();

        // ── 누적 풀이 / 정답률 ────────────────────────────────────
        int totalSolved = Math.toIntExact(userQuizAttemptRepository.countByUserId(userId));
        int totalCorrect = Math.toIntExact(userQuizAttemptRepository.countByUserIdAndFirstCorrectTrue(userId));
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

        return new UserStatsResponse(
            user.getNickname(),
            user.getCurrentStreak(),
            user.getMaxStreak(),
            totalSolved,
            correctRate,
            activityGrid
        );
    }

    // ── 연간 잔디밭 ──────────────────────────────────────────────────────────

    /** 잔디 기간: 오늘 포함 최근 365일. */
    private static final int GRASS_DAYS = 365;

    /** 하루 기본 퀴즈 세트 크기 — 완주(level 3+) 판정 기준. */
    private static final int DAILY_SET_SIZE = 4;

    /**
     * 연간 잔디밭 (GitHub contribution graph 스타일).
     *
     * 활동이 있는 날만 sparse 로 반환한다 — 신규 학습을 한 날 ∪ 복습만 한 날.
     * level 규칙과 스트릭·잔디의 축 구분은 {@link GrassResponse} javadoc 참조.
     */
    @Transactional
    public GrassResponse getGrass(Long userId) {
        User user = userService.synchronizeStreak(userId);
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(GRASS_DAYS - 1);

        Map<LocalDate, Integer> attemptsByDate = userQuizAttemptRepository
                .countAttemptsByDateBetween(userId, from, today).stream()
                .collect(Collectors.toMap(
                        row -> toLocalDate(row[0]),
                        row -> ((Number) row[1]).intValue()
                ));
        Map<LocalDate, Integer> correctByDate = userQuizAttemptRepository
                .countFirstCorrectByDateBetween(userId, from, today).stream()
                .collect(Collectors.toMap(
                        row -> toLocalDate(row[0]),
                        row -> ((Number) row[1]).intValue()
                ));
        Map<LocalDate, Integer> reviewedByDate = reviewDailyLogRepository
                .findAllByUserIdAndReviewDateBetween(userId, from, today).stream()
                .collect(Collectors.toMap(
                        ReviewDailyLog::getReviewDate,
                        ReviewDailyLog::getReviewedCount
                ));

        // 신규 학습을 한 날과 복습만 한 날의 합집합이 '활동일'이다.
        Set<LocalDate> activeDates = new java.util.TreeSet<>(attemptsByDate.keySet());
        activeDates.addAll(reviewedByDate.keySet());

        List<GrassResponse.GrassDay> days = activeDates.stream()
                .map(date -> {
                    int solved = attemptsByDate.getOrDefault(date, 0);
                    int correct = correctByDate.getOrDefault(date, 0);
                    int reviewed = reviewedByDate.getOrDefault(date, 0);
                    return new GrassResponse.GrassDay(
                            date, solved, correct, reviewed, grassLevel(solved, correct));
                })
                .toList();

        int perfectDays = Math.toIntExact(days.stream().filter(d -> d.level() == 4).count());

        return new GrassResponse(
                from, today,
                days.size(), perfectDays,
                user.getCurrentStreak(), user.getMaxStreak(),
                user.getGraduatedReviewCount(),
                days
        );
    }

    /**
     * 잔디 농도 (1~4). 활동 없는 날은 응답에서 제외되므로 0 은 없다.
     *  1 = 1~2문제, 또는 신규 학습 없이 복습만 한 날 (연한 잔디)
     *  2 = 3문제, 3 = 완주(4문제+), 4 = 완주 + 전부 정답 (만점 잔디)
     *
     * 복습 수를 인자로 받지 않는 이유: 잔디 농도는 '신규 학습'의 지표이며,
     * 복습을 아무리 많이 해도 level 1 을 넘지 않아야 한다 (solved=0 → 1).
     */
    private static int grassLevel(int solved, int correct) {
        if (solved == 0) {
            return 1; // 복습만 한 날 — 연한 잔디
        }
        if (solved >= DAILY_SET_SIZE) {
            return correct >= solved ? 4 : 3;
        }
        return solved >= 3 ? 2 : 1;
    }

    // ── 취약 개념 진단 ───────────────────────────────────────────────────────

    /** 진단에 필요한 카테고리별 최소 표본 수 — 미만이면 weakest 후보에서 제외. */
    private static final int MIN_ATTEMPTS_FOR_DIAGNOSIS = 3;

    /**
     * 카테고리(개념)별 정답률 + 가장 약한 개념.
     * weakest: 표본 {@value MIN_ATTEMPTS_FOR_DIAGNOSIS}개 이상인 카테고리 중 정답률 최저.
     * 동률이면 표본이 많은 쪽(더 신뢰할 수 있는 진단)을 고른다.
     */
    public ConceptStatsResponse getConceptStats(Long userId) {
        List<ConceptStatsResponse.CategoryStat> stats = userQuizAttemptRepository
                .countByCategory(userId).stream()
                .filter(row -> row[0] != null) // 카테고리 유실 데이터 방어
                .map(row -> {
                    Category category = Category.valueOf((String) row[0]);
                    int total = ((Number) row[1]).intValue();
                    int correct = ((Number) row[2]).intValue();
                    return new ConceptStatsResponse.CategoryStat(
                            category.name(),
                            category.getDisplayName(),
                            total,
                            correct,
                            total > 0 ? (float) correct / total : 0f
                    );
                })
                .sorted(java.util.Comparator.comparing(ConceptStatsResponse.CategoryStat::category))
                .toList();

        ConceptStatsResponse.CategoryStat weakest = stats.stream()
                .filter(s -> s.total() >= MIN_ATTEMPTS_FOR_DIAGNOSIS)
                .min(java.util.Comparator
                        .comparingDouble(ConceptStatsResponse.CategoryStat::correctRate)
                        .thenComparing(java.util.Comparator
                                .comparingInt(ConceptStatsResponse.CategoryStat::total).reversed()))
                .orElse(null);

        return new ConceptStatsResponse(stats, weakest);
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
