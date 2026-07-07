package com.example.pinq_backend.notification.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 알림 설정 변경 요청.
 *
 * @param enabled 알림 수신 여부 (필수)
 * @param time    수신 희망 시각 "HH:mm" (선택 — null 이면 기존 시각 유지).
 *                30분 단위(HH:00 / HH:30)만 허용.
 */
public record NotificationSettingsRequest(
        @NotNull Boolean enabled,
        String time
) {}
