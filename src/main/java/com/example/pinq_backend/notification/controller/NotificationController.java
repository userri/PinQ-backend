package com.example.pinq_backend.notification.controller;

import com.example.pinq_backend.auth.SecurityUtils;
import com.example.pinq_backend.notification.dto.DeviceTokenRequest;
import com.example.pinq_backend.notification.dto.NotificationSettingsRequest;
import com.example.pinq_backend.notification.dto.NotificationSettingsResponse;
import com.example.pinq_backend.notification.service.NotificationService;
import com.example.pinq_backend.user.service.UserService;
import jakarta.validation.Valid;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데일리 퀴즈 푸시 알림 설정 API.
 *
 *  GET    /api/users/me/notification-settings   → 현재 설정 조회
 *  PUT    /api/users/me/notification-settings   → on/off + 수신 시각(30분 단위) 변경
 *  POST   /api/users/me/device-tokens           → FCM 토큰 등록 (로그인/토큰 갱신 시)
 *  DELETE /api/users/me/device-tokens?token=..  → FCM 토큰 해제 (로그아웃 시)
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    @GetMapping("/notification-settings")
    public ResponseEntity<NotificationSettingsResponse> getSettings() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return ResponseEntity.ok(
                NotificationSettingsResponse.from(notificationService.getUser(userId)));
    }

    @PutMapping("/notification-settings")
    public ResponseEntity<NotificationSettingsResponse> updateSettings(
            @Valid @RequestBody NotificationSettingsRequest request
    ) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        notificationService.updateSettings(userId, request.enabled(), parseTime(request.time()));
        return ResponseEntity.ok(
                NotificationSettingsResponse.from(notificationService.getUser(userId)));
    }

    @PostMapping("/device-tokens")
    public ResponseEntity<Void> registerToken(@Valid @RequestBody DeviceTokenRequest request) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        notificationService.registerToken(userId, request.token());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/device-tokens")
    public ResponseEntity<Void> unregisterToken(@RequestParam("token") String token) {
        notificationService.unregisterToken(token);
        return ResponseEntity.noContent().build();
    }

    /** "HH:mm" → LocalTime. null 은 그대로 (기존 시각 유지), 형식 오류는 400. */
    private LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("시각 형식이 올바르지 않습니다 (HH:mm): " + time);
        }
    }
}
