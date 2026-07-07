package com.example.pinq_backend.notification.scheduler;

import com.example.pinq_backend.notification.service.NotificationService;
import java.time.Clock;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 30분 슬롯(:00, :30)마다 데일리 퀴즈 푸시 알림을 전송하는 스케줄러.
 *
 * 사용자의 notification_time 이 현재 슬롯과 일치하면 전송 대상이 된다.
 * 슬롯 계산은 현재 시각을 30분 단위로 '내림'해서 구한다 — 스케줄러 실행이
 * 몇 초 늦어져도(예: 09:00:07) 같은 09:00 슬롯으로 정규화된다.
 *
 * 중복 전송 방지는 NotificationService 의 NotificationLog 유니크 제약이 담당하므로
 * 이 스케줄러가 여러 인스턴스에서 동시에 돌아도 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "0 0,30 * * * *", zone = "Asia/Seoul")
    public void sendDailyQuizReminders() {
        LocalTime slot = currentSlot();
        try {
            notificationService.sendDailyReminders(slot);
        } catch (Exception e) {
            log.error("[알림 스케줄러] 슬롯 {} 전송 중 오류", slot, e);
        }
    }

    /** 현재 시각을 30분 단위로 내림한 슬롯 (09:00:07 → 09:00, 09:31 → 09:30). */
    private LocalTime currentSlot() {
        LocalTime now = LocalTime.now(clock);
        return LocalTime.of(now.getHour(), now.getMinute() < 30 ? 0 : 30);
    }
}
