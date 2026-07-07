package com.example.pinq_backend.notification.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 데일리 퀴즈 알림 전송 기록 (중복 전송 방지용).
 *
 * (user_id, sent_date, slot_time) 유니크 제약이 핵심이다:
 *  - 전송 전에 이 로그를 먼저 INSERT 하고, 제약 위반이 나면 "이미 전송됨"으로 간주해
 *    스킵한다. blue/green 두 인스턴스의 스케줄러가 동시에 돌아도 DB 가
 *    정확히 한 번만 허용하므로 사용자가 같은 알림을 두 번 받지 않는다.
 *  - 스케줄러 재시작·지연 재실행에도 같은 원리로 멱등하다.
 */
@Entity
@Table(
    name = "notification_log",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_log",
        columnNames = {"user_id", "sent_date", "slot_time"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sent_date", nullable = false)
    private LocalDate sentDate;

    @Column(name = "slot_time", nullable = false)
    private LocalTime slotTime;

    public static NotificationLog create(Long userId, LocalDate sentDate, LocalTime slotTime) {
        NotificationLog log = new NotificationLog();
        log.userId = userId;
        log.sentDate = sentDate;
        log.slotTime = slotTime;
        return log;
    }
}
