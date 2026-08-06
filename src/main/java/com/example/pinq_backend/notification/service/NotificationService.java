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

    /**
     * 알림 본문. <b>개수는 그날 실제 발행 수를 넣는다 — 상수로 두면 거짓이 된다.</b>
     * 종전 문구는 "방금 나온 경제 뉴스로 만든 4문제"였고 두 군데가 사실이 아니었다:
     *  - "방금": 알림은 사용자가 설정한 시각에 가고 발행은 그보다 앞선다. 서버가 보증 못 하는 시간 표현.
     *  - "4문제": 발행은 5카테고리 = 5문제가 기본이고, 리젝이 나면 그날만 줄어든다.
     */
    static String pushBody(long quizCount) {
        return "경제 뉴스로 만든 %d문제, 지금 풀어보세요!".formatted(quizCount);
    }

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

        long quizCount = quizRepository.countByQuizDate(today);
        if (quizCount == 0) {
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
                if (sendToUser(user, today, slot, quizCount)) {
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
    private boolean sendToUser(User user, LocalDate today, LocalTime slot, long quizCount) {
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
                    fcmPushClient.send(deviceToken.getToken(), PUSH_TITLE, pushBody(quizCount));
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
     * 디바이스 토큰 등록. <b>멱등하다</b> — 같은 토큰을 몇 번 등록해도 성공한다.
     * 앱 시작 시 재등록이 정상 흐름이라(프론트 `d68dfd7`) 중복 호출이 예외 상황이 아니다.
     *
     * 같은 토큰이 다른 사용자에게 있으면(같은 기기에서 계정 전환) 기존 행을 지우고
     * 새 소유자로 재등록한다 — 이전 계정으로 알림이 가는 것을 방지.
     *
     * <b>@Transactional 을 걸지 않는다.</b> 유니크 제약 위반을 잡아서 성공으로 넘겨야 하는데,
     * 하나의 트랜잭션 안에서 제약 위반이 나면 catch 해도 트랜잭션이 rollback-only 로 오염돼
     * 커밋 시점에 UnexpectedRollbackException 이 난다. 이 클래스의 sendDailyReminders 가
     * 같은 이유로 트랜잭션을 걸지 않으며, 조회·삭제·저장은 리포지토리의 기본 트랜잭션으로 처리한다.
     */
    public void registerToken(Long userId, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("디바이스 토큰이 비어 있습니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));

        var existing = deviceTokenRepository.findByToken(token);
        if (existing.isPresent()) {
            if (existing.get().getUser().getId().equals(userId)) {
                return; // 같은 사용자의 재등록 — 이미 목표 상태다
            }
            deviceTokenRepository.delete(existing.get()); // 소유자 이전
        }

        try {
            deviceTokenRepository.save(UserDeviceToken.create(user, token));
        } catch (DataIntegrityViolationException e) {
            // 위 조회와 이 INSERT 사이에 다른 요청이 같은 토큰을 넣은 경우 — 둘 다 "없음"을 보고
            // 둘 다 INSERT 한다(2026-08-06 실기기: 2초 간격 두 요청 중 뒤엣것이 500).
            // 최종 상태는 "그 토큰 행이 존재한다"로 같으므로 성공으로 처리한다.
            log.debug("디바이스 토큰 동시 등록 감지, 스킵. userId={}", userId);
        }
    }

    /** 디바이스 토큰 해제 (로그아웃 시). 존재하지 않아도 조용히 성공한다. */
    @Transactional
    public void unregisterToken(String token) {
        deviceTokenRepository.deleteByToken(token);
    }
}
