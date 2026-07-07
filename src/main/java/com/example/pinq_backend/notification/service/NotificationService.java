package com.example.pinq_backend.notification.service;

import com.example.pinq_backend.notification.client.FcmPushClient;
import com.example.pinq_backend.notification.domain.NotificationLog;
import com.example.pinq_backend.notification.domain.UserDeviceToken;
import com.example.pinq_backend.notification.repository.NotificationLogRepository;
import com.example.pinq_backend.notification.repository.UserDeviceTokenRepository;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데일리 퀴즈 푸시 알림 서비스.
 *
 * 전송 흐름 (30분 슬롯마다 스케줄러가 호출):
 *  1. 오늘 퀴즈가 없으면 전체 스킵 — "퀴즈 도착" 알림인데 퀴즈가 없으면 거짓말이 된다
 *  2. 알림 ON + 수신 시각 = 현재 슬롯인 사용자 조회
 *  3. 사용자별로 NotificationLog 를 먼저 INSERT — 유니크 제약(user, 날짜, 슬롯)이
 *     중복 전송을 DB 레벨에서 차단한다 (다중 인스턴스·재실행에도 멱등)
 *  4. 사용자의 모든 디바이스 토큰으로 FCM 전송, 무효 토큰은 즉시 삭제
 *
 * sendDailyReminders 가 @Transactional 이 아닌 이유:
 *  로그 INSERT 의 제약 위반을 사용자 단위로 잡고 계속 진행해야 하는데,
 *  하나의 큰 트랜잭션 안에서 제약 위반이 나면 트랜잭션 전체가 rollback-only 로
 *  오염된다. 사용자별 저장/삭제는 리포지토리의 기본 트랜잭션으로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final String PUSH_TITLE = "오늘의 경제 퀴즈가 도착했어요";
    static final String PUSH_BODY = "방금 나온 경제 뉴스로 만든 4문제, 지금 풀어보세요!";

    private final UserRepository userRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final QuizRepository quizRepository;
    private final FcmPushClient fcmPushClient;
    private final Clock clock;

    /**
     * 주어진 30분 슬롯의 사용자들에게 데일리 퀴즈 알림을 전송한다.
     *
     * @param slot 30분 슬롯 시각 (예: 09:00, 09:30). 스케줄러가 계산해 넘긴다.
     * @return 실제 전송(1개 이상 토큰 성공)한 사용자 수
     */
    public int sendDailyReminders(LocalTime slot) {
        LocalDate today = LocalDate.now(clock);

        if (quizRepository.countByQuizDate(today) == 0) {
            log.info("[알림] 오늘({}) 퀴즈가 없어 슬롯 {} 알림을 건너뜀", today, slot);
            return 0;
        }

        List<User> targets = userRepository.findAllByNotificationEnabledTrueAndNotificationTime(slot);
        if (targets.isEmpty()) {
            return 0;
        }
        log.info("[알림] 슬롯 {} 대상 사용자 {}명", slot, targets.size());

        int sentUsers = 0;
        for (User user : targets) {
            try {
                if (sendToUser(user, today, slot)) {
                    sentUsers++;
                }
            } catch (Exception e) {
                // 한 사용자의 실패가 나머지 사용자 전송을 막지 않게 한다
                log.error("[알림] 사용자 {} 전송 중 오류", user.getId(), e);
            }
        }
        log.info("[알림] 슬롯 {} 전송 완료. {}명", slot, sentUsers);
        return sentUsers;
    }

    /** @return 1개 이상의 토큰으로 실제 전송했으면 true */
    private boolean sendToUser(User user, LocalDate today, LocalTime slot) {
        // 전송 '전에' 로그를 남긴다 — 유니크 제약 위반이면 다른 인스턴스/재실행이
        // 이미 처리한 것이므로 스킵. (전송 후 기록 방식은 기록 직전 크래시 시 중복 발송 위험)
        try {
            notificationLogRepository.save(
                    NotificationLog.create(user.getId(), today, slot));
        } catch (DataIntegrityViolationException e) {
            log.debug("[알림] 이미 전송됨, 스킵. userId={}, slot={}", user.getId(), slot);
            return false;
        }

        List<UserDeviceToken> tokens = deviceTokenRepository.findAllByUserId(user.getId());
        if (tokens.isEmpty()) {
            log.debug("[알림] 디바이스 토큰 없음. userId={}", user.getId());
            return false;
        }

        boolean anySent = false;
        for (UserDeviceToken deviceToken : tokens) {
            FcmPushClient.SendResult result =
                    fcmPushClient.send(deviceToken.getToken(), PUSH_TITLE, PUSH_BODY);
            switch (result) {
                case SENT -> anySent = true;
                case INVALID_TOKEN -> deviceTokenRepository.delete(deviceToken);
                case ERROR, DISABLED -> { /* 토큰 유지. DISABLED 는 Firebase 미설정 환경 */ }
            }
        }
        return anySent;
    }

    // ── 사용자 설정 / 토큰 관리 (컨트롤러에서 사용) ────────────────────────────

    /** 알림 설정 변경. 시각은 30분 단위만 허용 (User 도메인에서 검증). */
    @Transactional
    public void updateSettings(Long userId, boolean enabled, LocalTime time) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));
        user.updateNotificationSettings(enabled, time);
    }

    @Transactional(readOnly = true)
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));
    }

    /**
     * 디바이스 토큰 등록.
     * 같은 토큰이 이미 있으면(같은 기기에서 재로그인·계정 전환) 기존 행을 지우고
     * 새 소유자로 재등록한다 — 이전 계정으로 알림이 가는 것을 방지.
     */
    @Transactional
    public void registerToken(Long userId, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("디바이스 토큰이 비어 있습니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));

        deviceTokenRepository.findByToken(token).ifPresent(existing -> {
            if (existing.getUser().getId().equals(userId)) {
                return; // 같은 사용자의 재등록 — 그대로 유지
            }
            deviceTokenRepository.delete(existing);
            deviceTokenRepository.flush(); // 유니크 제약 충돌 방지: 삭제를 먼저 반영
        });

        if (deviceTokenRepository.findByToken(token).isEmpty()) {
            deviceTokenRepository.save(UserDeviceToken.create(user, token));
        }
    }

    /** 디바이스 토큰 해제 (로그아웃 시). 존재하지 않아도 조용히 성공한다. */
    @Transactional
    public void unregisterToken(String token) {
        deviceTokenRepository.deleteByToken(token);
    }
}
