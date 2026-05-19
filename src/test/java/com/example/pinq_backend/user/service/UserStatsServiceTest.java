package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.example.pinq_backend.config.AppConfig;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.UserStatsResponse;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.lang.reflect.Field;
import java.time.Clock;
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
 *  4. activityGrid 에서 정답 수가 MAX_INTENSITY(4) 이상이면 4로 고정되는지
 *  5. 활동 없는 날은 0이다
 */
@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private UserQuizAttemptRepository userQuizAttemptRepository;

    @InjectMocks
    private UserStatsService userStatsService;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 14);
    private static final Clock FIXED_CLOCK =
        Clock.fixed(TODAY.atStartOfDay(AppConfig.KST).toInstant(), AppConfig.KST);

    private User demoUser;

    @BeforeEach
    void setUp() {
        setField(userStatsService, "clock", FIXED_CLOCK);

        demoUser = User.builder()
            .nickname("demo")
            .currentStreak(3)
            .maxStreak(5)
            .lastSolvedDate(TODAY)
            .build();
        setId(demoUser, 1L);
        given(userService.synchronizeDemoStreak()).willReturn(demoUser);
    }

    @Test
    @DisplayName("여러 날의 풀이 이력을 올바르게 합산한다")
    void getStats_aggregatesHistoryCorrectly() {
        given(userQuizAttemptRepository.countByUserId(1L)).willReturn(12L);
        given(userQuizAttemptRepository.countByUserIdAndFirstCorrectTrue(1L)).willReturn(9L);
        given(userQuizAttemptRepository.countAttemptsByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());
        given(userQuizAttemptRepository.countFirstCorrectByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.totalSolved()).isEqualTo(12);
        assertThat(result.correctRate()).isEqualTo(9f / 12f);
        assertThat(result.streak()).isEqualTo(3);
        assertThat(result.maxStreak()).isEqualTo(5);
    }

    @Test
    @DisplayName("풀이 이력이 없으면 correctRate 는 0, totalSolved 는 0 이다")
    void getStats_zeroHistory_correctRateIsZero() {
        given(userQuizAttemptRepository.countAttemptsByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());
        given(userQuizAttemptRepository.countFirstCorrectByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.totalSolved()).isZero();
        assertThat(result.correctRate()).isZero();
    }

    @Test
    @DisplayName("activityGrid 는 56개이고 index 0 이 55일 전, index 55 가 오늘이다")
    void getStats_activityGrid_sizeAndDirection() {
        // 오늘: 시도 2회 중 1개 정답 → intensity 2 / 55일 전: 시도는 했으나 정답 0개 → intensity 1
        Object[] todayAttempt  = new Object[]{TODAY,               2L};
        Object[] oldestAttempt = new Object[]{TODAY.minusDays(55), 1L};
        Object[] todayCorrect  = new Object[]{TODAY,               1L};

        given(userQuizAttemptRepository.countAttemptsByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of(todayAttempt, oldestAttempt));
        given(userQuizAttemptRepository.countFirstCorrectByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.<Object[]>of(todayCorrect)); // 55일 전은 정답 없음 → 목록에 없음

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.activityGrid()).hasSize(56);
        assertThat(result.activityGrid().get(55)).isEqualTo(2);  // 오늘: 정답 1개 → min(1+1,4)=2
        assertThat(result.activityGrid().get(0)).isEqualTo(1);   // 55일 전: 시도했으나 정답 0개 → 1
        assertThat(result.activityGrid().get(1)).isEqualTo(0);   // 54일 전 — 기록 없음 → 0
    }

    @Test
    @DisplayName("정답 수가 3 이상이면 강도는 4로 고정된다")
    void getStats_activityGrid_intensityCapAt4() {
        Object[] attemptRow = new Object[]{TODAY, 4L};  // 4회 시도
        Object[] correctRow = new Object[]{TODAY, 3L};  // 3개 정답 → min(3+1, 4) = 4

        given(userQuizAttemptRepository.countAttemptsByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.<Object[]>of(attemptRow));
        given(userQuizAttemptRepository.countFirstCorrectByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.<Object[]>of(correctRow));

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.activityGrid().get(55)).isEqualTo(4);
    }

    @Test
    @DisplayName("활동이 없는 날은 activityGrid 에서 0 이다")
    void getStats_activityGrid_noActivityIsZero() {
        given(userQuizAttemptRepository.countAttemptsByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());
        given(userQuizAttemptRepository.countFirstCorrectByDateBetween(
            1L, TODAY.minusDays(55), TODAY)
        ).willReturn(List.of());

        UserStatsResponse result = userStatsService.getStats();

        assertThat(result.activityGrid()).allMatch(v -> v == 0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void setId(Object entity, Long id) {
        setField(entity, "id", id);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "필드 세팅 실패: " + target.getClass().getSimpleName() + "#" + fieldName, e);
        }
    }
}
