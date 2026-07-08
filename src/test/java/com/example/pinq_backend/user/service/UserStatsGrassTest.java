package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.GrassResponse;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
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
 * 연간 잔디밭 응답 검증 — sparse 반환과 잔디 농도(level) 사다리.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserStatsGrassTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long USER_ID = 10L;

    @Mock private UserService userService;
    @Mock private UserQuizAttemptRepository userQuizAttemptRepository;

    private UserStatsService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        service = new UserStatsService(userService, userQuizAttemptRepository, clock);

        User user = User.builder().nickname("tester").build();
        user.syncStreak(3, 15, TODAY);
        when(userService.synchronizeStreak(USER_ID)).thenReturn(user);
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
    }
}
