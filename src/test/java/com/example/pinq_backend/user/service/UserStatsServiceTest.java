package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UserStatsService 집계 로직 검증.
 *
 *  1. 풀이 이력이 여러 날 있을 때 totalSolved / correctRate 가 올바르게 합산되는지
 *  2. 풀이 이력이 전혀 없을 때 correctRate 가 0 (0-division 방어)
 *  3. activityGrid 가 56개이고 index 방향이 올바른지 (index 0 = 55일 전, index 55 = 오늘)
 *  4. activityGrid 에서 solved_count=0 인 날은 false 로 처리되는지
 */
@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private SolvedHistoryRepository solvedHistoryRepository;

    @InjectMocks
    private UserStatsService userStatsService;

    private User demoUser;
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 14);

    @BeforeEach
    void setUp() {
        demoUser = User.builder()
            .nickname("demo")
            .currentStreak(3)
            .maxStreak(5)
            .lastSolvedDate(TODAY)
            .build();
        setId(demoUser, 1L);
        given(userService.findDemoUser()).willReturn(demoUser);
    }

    @Test
    @DisplayName("여러 날의 풀이 이력을 올바르게 합산한다")
    void getStats_aggregatesHistoryCorrectly() {
        SolvedHistory day1 = historyOf(TODAY.minusDays(2), 4, 3);
        SolvedHistory day2 = historyOf(TODAY.minusDays(1), 4, 2);
        SolvedHistory day3 = historyOf(TODAY,              4, 4);
        List<SolvedHistory> all = List.of(day1, day2, day3);

        given(solvedHistoryRepository.findByUserId(1L)).willReturn(all);
        given(solvedHistoryRepository.findByUserIdAndSolvedDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(all);

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.totalSolved()).isEqualTo(12);           // 4+4+4
        assertThat(result.correctRate()).isEqualTo(9f / 12f);     // 3+2+4=9
        assertThat(result.streak()).isEqualTo(3);
    }

    @Test
    @DisplayName("풀이 이력이 없으면 correctRate 는 0, totalSolved 는 0 이다")
    void getStats_zeroHistory_correctRateIsZero() {
        given(solvedHistoryRepository.findByUserId(1L)).willReturn(List.of());
        given(solvedHistoryRepository.findByUserIdAndSolvedDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.totalSolved()).isZero();
        assertThat(result.correctRate()).isZero();
    }

    @Test
    @DisplayName("activityGrid 는 56개이고 index 0 이 55일 전, index 55 가 오늘이다")
    void getStats_activityGrid_sizeAndDirection() {
        SolvedHistory todayHistory    = historyOf(TODAY, 1, 1);
        SolvedHistory oldestHistory   = historyOf(TODAY.minusDays(55), 1, 0);

        given(solvedHistoryRepository.findByUserId(1L)).willReturn(List.of());
        given(solvedHistoryRepository.findByUserIdAndSolvedDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of(todayHistory, oldestHistory));

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.activityGrid()).hasSize(56);
        assertThat(result.activityGrid().get(55)).isTrue();   // 오늘
        assertThat(result.activityGrid().get(0)).isTrue();    // 55일 전
        assertThat(result.activityGrid().get(1)).isFalse();   // 54일 전 — 기록 없음
    }

    @Test
    @DisplayName("solved_count=0 인 날은 activityGrid 에서 false 다")
    void getStats_activityGrid_zeroSolvedCountIsFalse() {
        // solved_count=0 인 행이 DB에 있더라도 활동한 날로 보지 않는다
        SolvedHistory emptyDay = historyOf(TODAY, 0, 0);

        given(solvedHistoryRepository.findByUserId(1L)).willReturn(List.of(emptyDay));
        given(solvedHistoryRepository.findByUserIdAndSolvedDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of(emptyDay));

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.activityGrid().get(55)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SolvedHistory historyOf(LocalDate date, int solved, int correct) {
        SolvedHistory h = SolvedHistory.create(demoUser, date);
        for (int i = 0; i < solved; i++) {
            h.record(i < correct);
        }
        return h;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("id 세팅 실패: " + entity.getClass(), e);
        }
    }
}
