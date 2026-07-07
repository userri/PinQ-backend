package com.example.pinq_backend.notification.dto;

import com.example.pinq_backend.user.domain.User;
import java.time.format.DateTimeFormatter;

/**
 * 알림 설정 조회 응답.
 *
 * @param enabled 알림 수신 여부
 * @param time    수신 희망 시각 "HH:mm" (설정 전이면 기본값 09:00)
 */
public record NotificationSettingsResponse(
        boolean enabled,
        String time
) {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static NotificationSettingsResponse from(User user) {
        var time = user.getNotificationTime() != null
                ? user.getNotificationTime()
                : User.DEFAULT_NOTIFICATION_TIME;
        return new NotificationSettingsResponse(user.isNotificationEnabled(), HH_MM.format(time));
    }
}
