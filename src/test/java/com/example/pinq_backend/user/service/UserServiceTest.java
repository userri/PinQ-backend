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
 *  5. 오늘 추가 풀이: 기존 SolvedHistory 누적 저장 호출 없음, solved+1 / correct+1 (정답)
 *  6. 오늘 추가 풀이: 기존 SolvedHistory 누적, solved+1 / correct 불변 (오답)
 *
 * [recordAnswer — streak]
 *  7. 풀이 이력 전무(lastSolvedDate=null): streak 1로 시작
 *  8. 어제 풀었으면: streak 연속 증가
 *  9. 이틀 전에 마지막으로 풀었으면: streak 1로 리셋
 * 10. 오늘 이미 풀었으면: streak 변화 없음
 *
 * [recordAnswer — demo 유저 자동 생성]
 * 11. demo 유저가 없을 때 recordAnswer: 유저 생성 후 streak=1, SolvedHistory 생성
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

    /** 기본 quizId. 첫 시도 시나리오에 사용. */
    private static final Long QUIZ_1 = 10L;
    private static final Long QUIZ_2 = 20L;

    private static final Clock FIXED_CLOCK =
        Clock.fixed(TODAY.atStartOfDay(AppConfig.KST).toInstant(), AppConfig.KST);

    @BeforeEach
    void injectClock() {
        setField(userService, "clock", FIXED_CLOCK);
        // 기본값: 어떤 quizId 든 첫 시도 (existsByUserIdAndQuizId=false).
        // 중복 시도 시나리오에서는 개별 테스트에서 override.
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
        SolvedHistory created = stubNoHistoryToday(user);

        userService.recordAnswer(QUIZ_1, true);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        verify(userQuizAttemptRepository).save(any(UserQuizAttempt.class));
        assertThat(created.getSolvedCount()).isEqualTo(1);
        assertThat(created.getCorrectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 첫 오답: SolvedHistory 를 새로 생성하고 solved+1 / correct 는 0 유지")
    void recordAnswer_firstAnswerToday_wrong() {
        User user = demoUser(1L, 0, null);
        stubExistingUser(user);
        SolvedHistory created = stubNoHistoryToday(user);

        userService.recordAnswer(QUIZ_1, false);

        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(created.getSolvedCount()).isEqualTo(1);
        assertThat(created.getCorrectCount()).isZero();
    }

    @Test
    @DisplayName("오늘 다른 문제 추가 정답: 기존 SolvedHistory 에 solved+1 / correct+1 누적, save 미호출")
    void recordAnswer_additionalAnswerToday_correct_differentQuiz() {
        User user = demoUser(1L, 1, TODAY);
        stubExistingUser(user);
        SolvedHistory existing = existingHistoryToday(user, 2, 1); // 기존: 2풀이 1정답

        userService.recordAnswer(QUIZ_2, true);  // 새 문제

        verify(solvedHistoryRepository, never()).save(any());
        assertThat(existing.getSolvedCount()).isEqualTo(3);
        assertThat(existing.getCorrectCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘 다른 문제 추가 오답: 기존 SolvedHistory 에 solved+1 / correct 불변 누적, save 미호출")
    void recordAnswer_additionalAnswerToday_wrong_differentQuiz() {
        User user = demoUser(1L, 1, TODAY);
        stubExistingUser(user);
        SolvedHistory existing = existingHistoryToday(user, 2, 2); // 기존: 2풀이 2정답

        userService.recordAnswer(QUIZ_2, false);  // 새 문제

        verify(solvedHistoryRepository, never()).save(any());
        assertThat(existing.getSolvedCount()).isEqualTo(3);
        assertThat(existing.getCorrectCount()).isEqualTo(2); // 오답이므로 correct 불변
    }

    @Test
    @DisplayName("같은 문제 재시도: 통계와 스트릭 모두 변하지 않고 조기 종료")
    void recordAnswer_duplicateAttempt_skipsAllUpdates() {
        User user = demoUser(1L, 3, TODAY);
        stubExistingUser(user);
        // 같은 quizId 가 이미 시도된 상태
        given(userQuizAttemptRepository.existsByUserIdAndQuizId(user.getId(), QUIZ_1))
            .willReturn(true);

        userService.recordAnswer(QUIZ_1, true);

        // 통계·시도·스트릭 어떤 것도 갱신되지 않아야 한다
        verify(userQuizAttemptRepository, never()).save(any());
        verify(solvedHistoryRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(user.getCurrentStreak()).isEqualTo(3);
    }

    // ── recordAnswer — streak ─────────────────────────────────────────────────

    @Test
    @DisplayName("풀이 이력이 전혀 없으면 streak 가 1 로 시작된다")
    void recordAnswer_noHistory_streakStartsAtOne() {
        User user = demoUser(1L, 0, null); // lastSolvedDate=null
        stubExistingUser(user);
        stubNoHistoryToday(user);

        userService.recordAnswer(QUIZ_1, true);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("어제 풀었으면 streak 가 연속으로 증가한다")
    void recordAnswer_solvedYesterday_streakIncreases() {
        User user = demoUser(1L, 3, YESTERDAY);
        stubExistingUser(user);
        stubNoHistoryToday(user);

        userService.recordAnswer(QUIZ_1, true);

        assertThat(user.getCurrentStreak()).isEqualTo(4);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("마지막 풀이가 이틀 전이면 streak 가 1 로 리셋된다")
    void recordAnswer_solvedTwoDaysAgo_streakResets() {
        User user = demoUser(1L, 5, TWO_DAYS_AGO);
        stubExistingUser(user);
        stubNoHistoryToday(user);

        userService.recordAnswer(QUIZ_1, false);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("오늘 다른 문제를 추가로 풀어도 streak 는 변하지 않는다")
    void recordAnswer_alreadySolvedToday_streakUnchanged() {
        User user = demoUser(1L, 2, TODAY); // 오늘 이미 기록됨
        stubExistingUser(user);
        existingHistoryToday(user, 1, 1);

        userService.recordAnswer(QUIZ_2, true);  // 같은 날 새 문제

        assertThat(user.getCurrentStreak()).isEqualTo(2); // 변화 없음
        assertThat(user.getLastSolvedDate()).isEqualTo(TODAY);
    }

    // ── recordAnswer — demo 유저 자동 생성 ────────────────────────────────────

    @Test
    @DisplayName("demo 유저가 없을 때 recordAnswer: 유저 생성 후 streak=1, SolvedHistory 신규 생성")
    void recordAnswer_demoUserAbsent_createsUserThenRecords() {
        User created = demoUser(1L, 0, null);
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(created);
        SolvedHistory newHistory = stubNoHistoryToday(created);

        userService.recordAnswer(QUIZ_1, true);

        // 유저 생성 확인
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getNickname()).isEqualTo("demo");

        // streak 갱신 확인
        assertThat(created.getCurrentStreak()).isEqualTo(1);

        // SolvedHistory 생성 및 통계 확인
        verify(solvedHistoryRepository).save(any(SolvedHistory.class));
        assertThat(newHistory.getSolvedCount()).isEqualTo(1);
        assertThat(newHistory.getCorrectCount()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** streak 과 lastSolvedDate 를 원하는 값으로 세팅한 demo 유저를 만든다. */
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

    /** userRepository 가 해당 유저를 반환하도록 stubbing. */
    private void stubExistingUser(User user) {
        given(userRepository.findByNickname(UserService.DEMO_NICKNAME))
            .willReturn(Optional.of(user));
    }

    /**
     * 오늘 SolvedHistory 가 없는 상황을 stubbing.
     * save() 가 호출되면 미리 만들어 둔 빈 SolvedHistory 를 돌려주어
     * 이후 record() 부작용을 테스트에서 직접 단언할 수 있게 한다.
     */
    private SolvedHistory stubNoHistoryToday(User user) {
        SolvedHistory fresh = SolvedHistory.create(user, TODAY);
        given(solvedHistoryRepository.findByUserIdAndSolvedDate(user.getId(), TODAY))
            .willReturn(Optional.empty());
        given(solvedHistoryRepository.save(any(SolvedHistory.class))).willReturn(fresh);
        return fresh;
    }

    /**
     * 오늘 SolvedHistory 가 이미 존재하는 상황을 stubbing.
     * solved / correct 초기값을 외부에서 지정할 수 있다.
     */
    private SolvedHistory existingHistoryToday(User user, int solved, int correct) {
        SolvedHistory history = SolvedHistory.create(user, TODAY);
        for (int i = 0; i < solved; i++) {
            history.record(i < correct);
        }
        given(solvedHistoryRepository.findByUserIdAndSolvedDate(user.getId(), TODAY))
            .willReturn(Optional.of(history));
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
