package com.example.pinq_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.notification.client.FcmPushClient;
import com.example.pinq_backend.notification.domain.NotificationLog;
import com.example.pinq_backend.notification.domain.UserDeviceToken;
import com.example.pinq_backend.notification.repository.NotificationLogRepository;
import com.example.pinq_backend.notification.repository.UserDeviceTokenRepository;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 7);
    private static final LocalTime SLOT = LocalTime.of(9, 0);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private UserRepository userRepository;
    @Mock private UserDeviceTokenRepository deviceTokenRepository;
    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private QuizRepository quizRepository;
    @Mock private FcmPushClient fcmPushClient;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(KST).toInstant(), KST);
        service = new NotificationService(
                userRepository, deviceTokenRepository, notificationLogRepository,
                quizRepository, fcmPushClient, clock);

        when(quizRepository.countByQuizDate(TODAY)).thenReturn(4L);
        when(notificationLogRepository.save(any(NotificationLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("오늘 퀴즈가 없으면 아무에게도 전송하지 않는다 — '퀴즈 도착' 알림이 거짓이 되므로")
    void noQuizToday_skipsAll() {
        when(quizRepository.countByQuizDate(TODAY)).thenReturn(0L);

        int sent = service.sendDailyReminders(SLOT);

        assertThat(sent).isZero();
        verify(userRepository, never()).findAllByNotificationEnabledTrueAndNotificationTime(any());
    }

    @Test
    @DisplayName("슬롯 사용자의 모든 기기로 전송하고, 전송 전에 로그를 남긴다")
    void sendsToSlotUsers_andLogsBeforeSending() {
        User user = userWithId(1L);
        when(userRepository.findAllByNotificationEnabledTrueAndNotificationTime(SLOT))
                .thenReturn(List.of(user));
        when(deviceTokenRepository.findAllByUserId(1L)).thenReturn(List.of(
                UserDeviceToken.create(user, "token-A"),
                UserDeviceToken.create(user, "token-B")
        ));
        when(fcmPushClient.send(anyString(), anyString(), anyString()))
                .thenReturn(FcmPushClient.SendResult.SENT);

        int sent = service.sendDailyReminders(SLOT);

        assertThat(sent).isEqualTo(1);
        verify(notificationLogRepository).save(any(NotificationLog.class));
        String body = NotificationService.pushBody(4L); // setUp 의 발행 수
        verify(fcmPushClient).send("token-A", NotificationService.PUSH_TITLE, body);
        verify(fcmPushClient).send("token-B", NotificationService.PUSH_TITLE, body);
    }

    @Test
    @DisplayName("로그 유니크 제약 위반이면 이미 전송된 것 — FCM 호출 없이 스킵 (다중 인스턴스 안전)")
    void duplicateLog_skipsSending() {
        User user = userWithId(1L);
        when(userRepository.findAllByNotificationEnabledTrueAndNotificationTime(SLOT))
                .thenReturn(List.of(user));
        when(notificationLogRepository.save(any(NotificationLog.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_log"));

        int sent = service.sendDailyReminders(SLOT);

        assertThat(sent).isZero();
        verify(fcmPushClient, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("무효 토큰(UNREGISTERED)은 즉시 삭제하고, 유효 토큰 전송은 계속한다")
    void invalidToken_isDeleted() {
        User user = userWithId(1L);
        UserDeviceToken dead = UserDeviceToken.create(user, "dead-token");
        UserDeviceToken alive = UserDeviceToken.create(user, "alive-token");
        when(userRepository.findAllByNotificationEnabledTrueAndNotificationTime(SLOT))
                .thenReturn(List.of(user));
        when(deviceTokenRepository.findAllByUserId(1L)).thenReturn(List.of(dead, alive));
        when(fcmPushClient.send(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "dead-token".equals(inv.getArgument(0))
                        ? FcmPushClient.SendResult.INVALID_TOKEN
                        : FcmPushClient.SendResult.SENT);

        int sent = service.sendDailyReminders(SLOT);

        assertThat(sent).isEqualTo(1);
        verify(deviceTokenRepository).delete(dead);
        verify(deviceTokenRepository, never()).delete(alive);
    }

    @Test
    @DisplayName("같은 토큰이 다른 계정 소유였으면 소유권을 새 계정으로 옮긴다 (기기 계정 전환)")
    void registerToken_movesOwnership() {
        User oldOwner = userWithId(1L);
        User newOwner = userWithId(2L);
        UserDeviceToken existing = UserDeviceToken.create(oldOwner, "shared-token");
        when(userRepository.findById(2L)).thenReturn(Optional.of(newOwner));
        when(deviceTokenRepository.findByToken("shared-token"))
                .thenReturn(Optional.of(existing))   // 등록 전 조회: 옛 소유자 행 존재
                .thenReturn(Optional.empty());       // 삭제 후 재조회: 없음 → 새로 저장
        when(deviceTokenRepository.save(any(UserDeviceToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.registerToken(2L, "shared-token");

        verify(deviceTokenRepository).delete(existing);
        verify(deviceTokenRepository).save(any(UserDeviceToken.class));
    }

    @Test
    @DisplayName("같은 토큰을 다시 등록해도 성공한다 — INSERT 없이 그대로 둔다")
    void registerToken_isIdempotent_forSameUser() {
        User user = userWithId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByToken("token-A"))
                .thenReturn(Optional.of(UserDeviceToken.create(user, "token-A")));

        service.registerToken(1L, "token-A");

        verify(deviceTokenRepository, never()).save(any(UserDeviceToken.class));
        verify(deviceTokenRepository, never()).delete(any(UserDeviceToken.class));
    }

    @Test
    @DisplayName("조회와 INSERT 사이에 다른 요청이 같은 토큰을 넣어도 500 이 아니라 성공이다")
    void registerToken_concurrentInsert_isSwallowed() {
        // 앱 시작 재등록이 정상 흐름이라 중복 호출이 겹치는 게 예외 상황이 아니다.
        // 두 요청이 모두 "없음"을 보고 둘 다 INSERT 하면 뒤엣것이 유니크 제약에 걸린다
        // (2026-08-06 실기기 재현: uk_device_token Duplicate entry → 500).
        User user = userWithId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByToken("token-A")).thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any(UserDeviceToken.class)))
                .thenThrow(new DataIntegrityViolationException("uk_device_token"));

        assertThatCode(() -> service.registerToken(1L, "token-A")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("알림 본문의 문제 수는 그날 실제 발행 수다 — 상수가 아니다")
    void pushBody_usesActualQuizCount() {
        User user = userWithId(1L);
        when(quizRepository.countByQuizDate(TODAY)).thenReturn(5L);
        when(userRepository.findAllByNotificationEnabledTrueAndNotificationTime(SLOT))
                .thenReturn(List.of(user));
        when(deviceTokenRepository.findAllByUserId(1L))
                .thenReturn(List.of(UserDeviceToken.create(user, "token-A")));
        when(fcmPushClient.send(anyString(), anyString(), anyString()))
                .thenReturn(FcmPushClient.SendResult.SENT);

        service.sendDailyReminders(SLOT);

        verify(fcmPushClient).send("token-A", NotificationService.PUSH_TITLE,
                "경제 뉴스로 만든 5문제, 지금 풀어보세요!");
    }

    /** JPA id 는 애플리케이션에서 세팅 불가 — 테스트에서 reflection 으로 주입. */
    private User userWithId(Long id) {
        User user = User.builder().nickname("user-" + id).build();
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return user;
    }
}
