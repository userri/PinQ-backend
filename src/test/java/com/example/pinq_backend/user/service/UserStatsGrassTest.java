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
    @Mock private com.example.pinq_backend.quiz.repository.QuizRepository quizRepository;

    private UserStatsService service;
    private User user;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        service = new UserStatsService(
                userService, userQuizAttemptRepository, reviewDailyLogRepository, quizRepository, clock);

        user = User.builder().nickname("tester").build();
        user.syncStreak(3, 15, TODAY);
        when(userService.synchronizeStreak(USER_ID)).thenReturn(user);
        when(reviewDailyLogRepository.findAllByUserIdAndReviewDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("활동일만 sparse 로 반환하고, 레벨 사다리(1~2문제/3문제/완주/만점)를 적용한다")
    void grass_levelsAndSparse() {
        LocalDate d1 = TODAY.minusDays(4); // 1문제 → level 1
        LocalDate d2 = TODAY.minusDays(3); // 3문제 → level 2
        LocalDate d3 = TODAY.minusDays(2); // 4문제 완주, 3정답 → level 3
        LocalDate d4 = TODAY.minusDays(1); // 4문제 완주, 4정답 → level 4 (만점 잔디)

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
    @DisplayName("결손일(3문제 발행)엔 3문제 전부 정답이면 만점, 백필로 5문제가 돼도 4문제 만점은 유지된다")
    void grass_targetIsMinOfCapAndPublished() {
        LocalDate shortDay = TODAY.minusDays(2);   // 3문제만 발행된 날 — 3/3 정답
        LocalDate backfillDay = TODAY.minusDays(1); // 백필로 5문제 발행 — 4/4 정답

        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{shortDay, 3L},
                        new Object[]{backfillDay, 4L}
                ));
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{shortDay, 3L},
                        new Object[]{backfillDay, 4L}
                ));
        when(quizRepository.countPublishedByDateBetween(any(), any()))
                .thenReturn(List.of(
                        publishedRow(shortDay, 3L),
                        publishedRow(backfillDay, 5L)
                ));

        GrassResponse grass = service.getGrass(USER_ID);

        // 3발행일: min(4,3)=3 → 3/3 만점 / 5발행일: min(4,5)=4 → 4/4 만점 (소급 강등 없음)
        assertThat(grass.days()).extracting(GrassResponse.GrassDay::level)
                .containsExactly(4, 4);
        assertThat(grass.perfectDays()).isEqualTo(2);
    }

    private com.example.pinq_backend.quiz.repository.QuizRepository.PublishedCountRow publishedRow(
            LocalDate date, Long cnt) {
        return new com.example.pinq_backend.quiz.repository.QuizRepository.PublishedCountRow() {
            @Override public LocalDate getQuizDate() { return date; }
            @Override public Long getCnt() { return cnt; }
        };
    }

    @Test
    @DisplayName("신규 학습 없이 복습만 한 날도 활동일로 잡히고 연한 잔디(level 1)가 심어진다")
    void grass_reviewOnlyDay_isLevelOne() {
        LocalDate reviewOnly = TODAY.minusDays(2);

        when(userQuizAttemptRepository.countAttemptsByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(userQuizAttemptRepository.countFirstCorrectByDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(reviewDailyLogRepository.findAllByUserIdAndReviewDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(dailyLog(reviewOnly, 9, 7)));

        GrassResponse grass = service.getGrass(USER_ID);

        assertThat(grass.totalActiveDays()).isEqualTo(1);
        assertThat(grass.days()).singleElement().satisfies(day -> {
            assertThat(day.date()).isEqualTo(reviewOnly);
            assertThat(day.solved()).isZero();
            assertThat(day.reviewed()).isEqualTo(9);
            assertThat(day.level()).isEqualTo(1); // 복습 9개를 해도 연한 잔디
        });
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
