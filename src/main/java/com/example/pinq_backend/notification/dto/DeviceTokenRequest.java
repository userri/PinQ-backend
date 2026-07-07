package com.example.pinq_backend.notification.dto;

import jakarta.validation.constraints.NotBlank;

/** FCM 디바이스 토큰 등록 요청. */
public record DeviceTokenRequest(
        @NotBlank String token
) {}
