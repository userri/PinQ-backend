package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.pinq_backend.config.AppConfig;
import com.example.pinq_backend.user.domain.SolvedHistory;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.repository.SolvedHistoryRepository;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * UserService 핵심 동작 검증.
 *
 * [findDemoUser]
 *  1. demo 유저가 없으면 새로 생성해 반환한다
 *  2. demo 유저가 이미 있으면 저장 없이 반환한다
 *
 * [recordAnswer — SolvedHistory]
 *  3. 오늘 첫 풀이: SolvedHistory 신규 생성, solved+1 / correct+1 (정답)
 *  4. 오늘 첫 풀이: SolvedHistory 신규 생성, solved+1 / correct 불변 (오답)
 *  5. 오늘 추가 풀이: 기존 SolvedHistory 에 solved+1 / correct+1 누적 후 save 호출
 *  6. 오늘 추가 풀이: 기존 SolvedHistory 에 solved+1 / correct 불변 누적 후 save 호출
 *
 * [recordAnswer — streak]
 *  7. 풀이 이력 전무(lastSolvedDate=null): streak 1로 시작
 *  8. 어제 풀었으면: streak 연속 증가
 *  9. 이틀 전에 마지막으로 풀었으면: streak 1로 리셋
 * 10. 오늘 이미 풀었으면: streak 변화 없음
 *
 * [recordAnswer — demo 유저 자동 생성]
 * 11. demo 유저가 없을 때 recordAnswer: 유저 생성 후 streak=1, SolvedHistory 생성
 *
 * [recordAnswer — 동시성]
 * 12. exists=false 확인 후 saveAndFlush 에서 UK 위반 시: 통계 갱신 없이 조용히 종료
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SolvedHistoryRepository solvedHistoryRepository;

    @Mock
    private UserQuizAttemptRepository userQuizAttemptRepository;

    @InjectMocks
    private UserService userService;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 14);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate TWO_DAYS_AGO = TODAY.minusDays(2);

    private static final Long QUIZ_1 = 10L;
    private static final Long QUIZ_2 = 20L;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(TODAY.atStartOfDay(AppConfig.KST).toInstant(), AppConfig.KST);

    @BeforeEach
    void injectClock() {
        setField(userService, "clock", FIXED_CLOCK);
        lenient().when(userQuizAttemptRepository.existsByUserIdAndQuizId(anyLong(), anyLong()))
                .thenReturn(false);
    }

    // ── findDemoUser ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("demo 유저가 없으면 새로 생성하여 반환한다")
    void findDemoUser_createsWhenAbsent() {
        User saved = demoUser(1L, 0, null);
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
        User existing = demoUser(1L, 3, YESTERDAY);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME))
                .willReturn(Optional.of(existing));

        User result = userService.findDemoUser();

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any());
    }

    // ── recordAnswer — SolvedHistory ─────────────────────────────────────────

    @Test
    @DisplayName("오늘 첫 정답: SolvedHistory 를 새로 생성하고 solved+1 / correct+1")
    void recordAnswer_firstAnswerToday_correct() {
        User user = demoUser(1L, 0, null);
        stubExistingUser(user);
        SolvedHistory[] captured = captureHistoryOnSave(user);

        userService.recordAnswer(QUIZ_1, true);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        verify(userQuizAttemptRepository).saveAndFlush(any(UserQuizAttempt.class));
        assertThat(captured[0].getSolvedCount()).isEqualTo(1);
        assertThat(captured[0].getCorrectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 첫 오답: SolvedHistory 를 새로 생성하고 solved+1 / correct 는 0 유지")
    void recordAnswer_firstAnswerToday_wrong() {
        User user = demoUser(1L, 0, null);
        stubExistingUser(user);
        SolvedHistory[] captured = captureHistoryOnSave(user);

        userService.recordAnswer(QUIZ_1, false);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(captured[0].getSolvedCount()).isEqualTo(1);
        assertThat(captured[0].getCorrectCount()).isZero();
    }

    @Test
    @DisplayName("오늘 다른 문제 추가 정답: 기존 SolvedHistory 에 solved+1 / correct+1 누적 후 save 호출")
    void recordAnswer_additionalAnswerToday_correct_differentQuiz() {
        User user = demoUser(1L, 1, TODAY);
        stubExistingUser(user);
        SolvedHistory existing = existingHistoryToday(user, 2, 1);

        userService.recordAnswer(QUIZ_2, true);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(existing.getSolvedCount()).isEqualTo(3);
        assertThat(existing.getCorrectCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘 다른 문제 추가 오답: 기존 SolvedHistory 에 solved+1 / correct 불변 누적 후 save 호출")
    void recordAnswer_additionalAnswerToday_wrong_differentQuiz() {
        User user = demoUser(1L, 1, TODAY);
        stubExistingUser(user);
        SolvedHistory existing = existingHistoryToday(user, 2, 2);

        userService.recordAnswer(QUIZ_2, false);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(existing.getSolvedCount()).isEqualTo(3);
        assertThat(existing.getCorrectCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 문제 재시도: 통계와 스트릭 모두 변하지 않고 조기 종료")
    void recordAnswer_duplicateAttempt_skipsAllUpdates() {
        User user = demoUser(1L, 3, TODAY);
        stubExistingUser(user);
        given(userQuizAttemptRepository.existsByUserIdAndQuizId(user.getId(), QUIZ_1))
                .willReturn(true);

        userService.recordAnswer(QUIZ_1, true);

        verify(userQuizAttemptRepository, never()).saveAndFlush(any());
        verify(solvedHistoryRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(user.getCurrentStreak()).isEqualTo(3);
    }

    // ── recordAnswer — 동시성 ──────────────────────────────────────────────────

    @Test
    @DisplayName("동시 요청으로 saveAndFlush 에서 UK 위반 발생 시: 통계/스트릭 갱신 없이 조용히 종료")
    void recordAnswer_concurrentDuplicate_dataIntegrityViolation_skipsStats() {
        User user = demoUser(1L, 2, YESTERDAY);
        stubExistingUser(user);
        // exists=false 로 통과했지만 flush 시 UK 위반 발생 (race condition 시뮬레이션)
        given(userQuizAttemptRepository.saveAndFlush(any(UserQuizAttempt.class)))
                .willThrow(new DataIntegrityViolationException("uk_user_quiz_attempt"));

        userService.recordAnswer(QUIZ_1, true);

        // streak·SolvedHistory 갱신 없이 종료되어야 한다
        verify(solvedHistoryRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(user.getCurrentStreak()).isEqualTo(2); // 변화 없음
    }

    // ── recordAnswer — streak ─────────────────────────────────────────────────

    @Test
    @DisplayName("풀이 이력이 전혀 없으면 streak 가 1 로 시작된다")
    void recordAnswer_noHistory_streakStartsAtOne() {
        User user = demoUser(1L, 0, null);
        stubExistingUser(user);
        captureHistoryOnSave(user);

        userService.recordAnswer(QUIZ_1, true);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("어제 풀었으면 streak 가 연속으로 증가한다")
    void recordAnswer_solvedYesterday_streakIncreases() {
        User user = demoUser(1L, 3, YESTERDAY);
        stubExistingUser(user);
        captureHistoryOnSave(user);

        userService.recordAnswer(QUIZ_1, true);

        assertThat(user.getCurrentStreak()).isEqualTo(4);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("마지막 풀이가 이틀 전이면 streak 가 1 로 리셋된다")
    void recordAnswer_solvedTwoDaysAgo_streakResets() {
        User user = demoUser(1L, 5, TWO_DAYS_AGO);
        stubExistingUser(user);
        captureHistoryOnSave(user);

        userService.recordAnswer(QUIZ_1, false);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("오늘 다른 문제를 추가로 풀어도 streak 는 변하지 않는다")
    void recordAnswer_alreadySolvedToday_streakUnchanged() {
        User user = demoUser(1L, 2, TODAY);
        stubExistingUser(user);
        existingHistoryToday(user, 1, 1);

        userService.recordAnswer(QUIZ_2, true);

        assertThat(user.getCurrentStreak()).isEqualTo(2);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    // ── recordAnswer — demo 유저 자동 생성 ────────────────────────────────────

    @Test
    @DisplayName("demo 유저가 없을 때 recordAnswer: 유저 생성 후 streak=1, SolvedHistory 신규 생성")
    void recordAnswer_demoUserAbsent_createsUserThenRecords() {
        User created = demoUser(1L, 0, null);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(created);
        SolvedHistory[] captured = captureHistoryOnSave(created);

        userService.recordAnswer(QUIZ_1, true);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, org.mockito.Mockito.times(2)).save(userCaptor.capture());
        assertThat(userCaptor.getAllValues().get(0).getNickname()).isEqualTo("demo");

        assertThat(created.getCurrentStreak()).isEqualTo(1);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(captured[0].getSolvedCount()).isEqualTo(1);
        assertThat(captured[0].getCorrectCount()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User demoUser(Long id, int streak, LocalDate lastSolvedDate) {
        User user = User.builder()
                .nickname("demo")
                .currentStreak(streak)
                .maxStreak(streak)
                .lastSolvedDate(lastSolvedDate)
                .build();
        setField(user, "id", id);
        return user;
    }

    private void stubExistingUser(User user) {
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME))
                .willReturn(Optional.of(user));
    }

    /**
     * 오늘 SolvedHistory 없는 상황 stubbing.
     * saveAndFlush() 가 호출될 때 전달된 attempt 객체를 그대로 반환한다.
     * save() 가 호출될 때 전달된 history 객체를 captured[0] 에 담아 반환한다.
     */
    private SolvedHistory[] captureHistoryOnSave(User user) {
        SolvedHistory[] captured = new SolvedHistory[1];
        given(solvedHistoryRepository.findByUserIdAndSolvedDate(user.getId(), TODAY))
                .willReturn(Optional.empty());
        given(userQuizAttemptRepository.saveAndFlush(any(UserQuizAttempt.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(solvedHistoryRepository.save(any(SolvedHistory.class)))
                .willAnswer(inv -> {
                    captured[0] = inv.getArgument(0);
                    return captured[0];
                });
        return captured;
    }

    /**
     * 오늘 SolvedHistory 이미 존재하는 상황 stubbing.
     */
    private SolvedHistory existingHistoryToday(User user, int solved, int correct) {
        SolvedHistory history = SolvedHistory.create(user, TODAY);
        for (int i = 0; i < solved; i++) {
            history.record(i < correct);
        }
        given(solvedHistoryRepository.findByUserIdAndSolvedDate(user.getId(), TODAY))
                .willReturn(Optional.of(history));
        given(userQuizAttemptRepository.saveAndFlush(any(UserQuizAttempt.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(solvedHistoryRepository.save(any(SolvedHistory.class))).willReturn(history);
        return history;
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