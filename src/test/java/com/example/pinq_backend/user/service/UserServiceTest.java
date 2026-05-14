package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.pinq_backend.config.AppConfig;
import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import com.example.pinq_backend.user.repository.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UserService 핵심 동작 검증.
 *
 *  1. demo 유저가 없으면 새로 생성해 반환한다 (findOrCreate)
 *  2. demo 유저가 이미 있으면 저장 없이 반환한다
 *  3. recordAnswer — 오늘 첫 풀이: SolvedHistory 신규 생성 + 통계 기록
 *  4. recordAnswer — 오늘 두 번째 풀이: 기존 SolvedHistory 에 누적
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SolvedHistoryRepository solvedHistoryRepository;

    @InjectMocks
    private UserService userService;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 14);
    private static final Clock FIXED_CLOCK =
        Clock.fixed(TODAY.atStartOfDay(AppConfig.KST).toInstant(), AppConfig.KST);

    @BeforeEach
    void injectClock() {
        setField(userService, "clock", FIXED_CLOCK);
    }

    // ── findDemoUser ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("demo 유저가 없으면 새로 생성하여 반환한다")
    void findDemoUser_createsWhenAbsent() {
        User saved = demoUser(1L, 0);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(saved);

        User result = userService.findDemoUser();

        assertThat(result.getNickname()).isEqualTo("demo");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("demo");
        assertThat(captor.getValue().getCurrentStreak()).isZero();
    }

    @Test
    @DisplayName("demo 유저가 이미 있으면 저장 없이 그대로 반환한다")
    void findDemoUser_returnsExistingWithoutSave() {
        User existing = demoUser(1L, 3);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME))
            .willReturn(Optional.of(existing));

        User result = userService.findDemoUser();

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any());
    }

    // ── recordAnswer ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("오늘 첫 풀이이면 SolvedHistory 를 새로 생성하고 통계를 기록한다")
    void recordAnswer_firstAnswerToday_createsSolvedHistory() {
        User user = demoUser(1L, 0);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME))
            .willReturn(Optional.of(user));

        SolvedHistory newHistory = SolvedHistory.create(user, TODAY);
        given(solvedHistoryRepository.findByUserIdAndSolvedDate(1L, TODAY))
            .willReturn(Optional.empty());
        given(solvedHistoryRepository.save(any(SolvedHistory.class))).willReturn(newHistory);

        userService.recordAnswer(true);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(newHistory.getSolvedCount()).isEqualTo(1);
        assertThat(newHistory.getCorrectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 이미 풀이 기록이 있으면 기존 SolvedHistory 에 누적한다")
    void recordAnswer_subsequentAnswerToday_accumulatesHistory() {
        User user = demoUser(1L, 0);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME))
            .willReturn(Optional.of(user));

        SolvedHistory existing = SolvedHistory.create(user, TODAY);
        existing.record(true); // 이미 한 번 풀었음
        given(solvedHistoryRepository.findByUserIdAndSolvedDate(1L, TODAY))
            .willReturn(Optional.of(existing));

        userService.recordAnswer(false);

        verify(solvedHistoryRepository, never()).save(any());
        assertThat(existing.getSolvedCount()).isEqualTo(2);
        assertThat(existing.getCorrectCount()).isEqualTo(1); // 첫 번째만 정답
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User demoUser(Long id, int streak) {
        User user = User.builder()
            .nickname("demo")
            .currentStreak(streak)
            .maxStreak(streak)
            .build();
        setField(user, "id", id);
        return user;
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
