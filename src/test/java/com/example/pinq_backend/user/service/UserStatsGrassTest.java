package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.review.domain.ReviewDailyLog;
import com.example.pinq_backend.review.repository.ReviewDailyLogRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.GrassResponse;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 연간 잔디밭 검증 — sparse 반환, 잔디 농도 사다리, 복습만 한 날 처리, 나무 카운터.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserStatsGrassTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long USER_ID = 10L;

    @Mock private UserService userService;
    @Mock private UserQuizAttemptRepository userQuizAttemptRepository;
    @Mock private ReviewDailyLogRepository reviewDailyLogRepository;

    private UserStatsService service;
    private User user;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        service = new UserStatsService(
                userService, userQuizAttemptRepository, reviewDailyLogRepository, clock);

        user = User.builder().nickname("tester").build();
        user.syncStreak(3, 15, TODAY);
        when(userService.synchronizeStreak(USER_ID)).thenReturn(user);
        when(reviewDailyLogRepository.findAllByUserIdAndReviewDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("활동일만 sparse 로 반환하고, 맞힌 개수로 레벨 사다리(1/2/3/4정답)를 적용한다")
    void grass_levelsAndSparse() {
        LocalDate d1 = TODAY.minusDays(4); // 1문제 풀고 0정답 → level 1
        LocalDate d2 = TODAY.minusDays(3); // 2정답 → level 2
        LocalDate d3 = TODAY.minusDays(2); // 3정답 → level 3
        LocalDate d4 = TODAY.minusDays(1); // 4정답 → level 4 (라임)

        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{d1, 1L},
                        new Object[]{d2, 3L},
                        new Object[]{d3, 4L},
                        new Object[]{d4, 4L}
                ));
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{d2, 2L},
                        new Object[]{d3, 3L},
                        new Object[]{d4, 4L}
                ));

        GrassResponse grass = service.getGrass(USER_ID);

        assertThat(grass.from()).isEqualTo(TODAY.minusDays(364));
        assertThat(grass.to()).isEqualTo(TODAY);
        assertThat(grass.totalActiveDays()).isEqualTo(4); // 활동일만 — 365개 아님
        assertThat(grass.perfectDays()).isEqualTo(1);
        assertThat(grass.currentStreak()).isEqualTo(3);
        assertThat(grass.maxStreak()).isEqualTo(15);

        assertThat(grass.days()).extracting(GrassResponse.GrassDay::date)
                .containsExactly(d1, d2, d3, d4); // 날짜 오름차순
        assertThat(grass.days()).extracting(GrassResponse.GrassDay::level)
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("라임(4)은 맞힌 개수 4 이상이면 성립한다 — 발행 수·완주 여부·틀린 문항 섞임과 무관")
    void grass_limeDependsOnlyOnCorrectCount() {
        LocalDate mixedDay = TODAY.minusDays(2);  // 6문제 풀고 4정답(2오답 섞임) → 라임
        LocalDate pastDay = TODAY.minusDays(1);   // 밀린 과거 문제 포함 4정답 → 라임

        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{mixedDay, 6L},
                        new Object[]{pastDay, 4L}
                ));
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{mixedDay, 4L},
                        new Object[]{pastDay, 4L}
                ));

        GrassResponse grass = service.getGrass(USER_ID);

        // 종전 규칙(완주 + 전부 정답)이었다면 mixedDay 는 오답이 섞여 3 이었다.
        assertThat(grass.days()).extracting(GrassResponse.GrassDay::level)
                .containsExactly(4, 4);
        assertThat(grass.perfectDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("복습만 한 날은 잔디 칸이 생기지 않는다 — 복습은 나무로만 표현 (2026-08-08 개정)")
    void grass_reviewOnlyDay_hasNoCell() {
        LocalDate reviewOnly = TODAY.minusDays(2);

        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(reviewDailyLogRepository.findAllByUserIdAndReviewDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(dailyLog(reviewOnly, 9, 7)));

        GrassResponse grass = service.getGrass(USER_ID);

        assertThat(grass.totalActiveDays()).isZero();
        assertThat(grass.days()).isEmpty(); // 복습 9개를 해도 칸 없음 — 성과는 나무 카운터로
        assertThat(grass.perfectDays()).isZero();
    }

    @Test
    @DisplayName("같은 날 신규 학습 + 복습을 함께 하면 level 은 신규 학습만으로 정해지고 reviewed 가 병기된다")
    void grass_sameDayQuizAndReview_levelFromQuizOnly() {
        LocalDate day = TODAY.minusDays(1);

        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{day, 4L}));
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{day, 4L}));
        when(reviewDailyLogRepository.findAllByUserIdAndReviewDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(dailyLog(day, 5, 3)));

        GrassResponse grass = service.getGrass(USER_ID);

        assertThat(grass.totalActiveDays()).isEqualTo(1); // 합집합이므로 중복 계산 안 함
        assertThat(grass.days()).singleElement().satisfies(d -> {
            assertThat(d.level()).isEqualTo(4);   // 만점 — 복습이 끌어올린 게 아님
            assertThat(d.solved()).isEqualTo(4);
            assertThat(d.reviewed()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("졸업한 문제 수가 나무 그루로 응답에 담긴다")
    void grass_graduatedTrees() throws Exception {
        setGraduatedCount(user, 12);
        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        GrassResponse grass = service.getGrass(USER_ID);

        assertThat(grass.graduatedTrees()).isEqualTo(12);
    }

    @Test
    @DisplayName("활동이 전혀 없으면 빈 잔디밭을 반환한다")
    void grass_empty() {
        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        GrassResponse grass = service.getGrass(USER_ID);

        assertThat(grass.days()).isEmpty();
        assertThat(grass.totalActiveDays()).isZero();
        assertThat(grass.perfectDays()).isZero();
        assertThat(grass.graduatedTrees()).isZero();
    }

    private ReviewDailyLog dailyLog(LocalDate date, int reviewed, int correct) {
        ReviewDailyLog log = ReviewDailyLog.firstReviewOfDay(user, date, correct > 0);
        for (int i = 1; i < reviewed; i++) {
            log.record(i < correct);
        }
        return log;
    }

    /** graduatedReviewCount 는 UPDATE 쿼리로만 증가하므로 테스트에선 reflection 으로 세팅. */
    private void setGraduatedCount(User user, int count) throws Exception {
        Field field = User.class.getDeclaredField("graduatedReviewCount");
        field.setAccessible(true);
        field.set(user, count);
    }
}
