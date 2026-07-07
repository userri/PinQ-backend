package com.example.pinq_backend.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserNotificationSettingsTest {

    private User user() {
        return User.builder().nickname("tester").build();
    }

    @Test
    @DisplayName("신규 사용자는 알림 꺼짐 + 기본 시각 09:00")
    void defaults() {
        User user = user();

        assertThat(user.isNotificationEnabled()).isFalse();
        assertThat(user.getNotificationTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    @DisplayName("30분 단위 시각(HH:00, HH:30)은 허용된다")
    void thirtyMinuteSlots_allowed() {
        User user = user();

        user.updateNotificationSettings(true, LocalTime.of(7, 0));
        assertThat(user.getNotificationTime()).isEqualTo(LocalTime.of(7, 0));

        user.updateNotificationSettings(true, LocalTime.of(21, 30));
        assertThat(user.getNotificationTime()).isEqualTo(LocalTime.of(21, 30));
        assertThat(user.isNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("30분 단위가 아닌 시각은 거부된다 — 스케줄러가 스캔하지 않는 시각이므로")
    void nonSlotTime_rejected() {
        User user = user();

        assertThatThrownBy(() -> user.updateNotificationSettings(true, LocalTime.of(9, 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30분 단위");
        assertThatThrownBy(() -> user.updateNotificationSettings(true, LocalTime.of(9, 0, 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시각을 null 로 주면 기존 시각을 유지한 채 on/off 만 바뀐다")
    void nullTime_keepsExisting() {
        User user = user();
        user.updateNotificationSettings(true, LocalTime.of(8, 30));

        user.updateNotificationSettings(false, null);

        assertThat(user.isNotificationEnabled()).isFalse();
        assertThat(user.getNotificationTime()).isEqualTo(LocalTime.of(8, 30));
    }
}
