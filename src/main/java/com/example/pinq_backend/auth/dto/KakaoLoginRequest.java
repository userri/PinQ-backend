package com.example.pinq_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 카카오 로그인 요청.
 * Android 앱이 Kakao SDK 에서 발급받은 accessToken 을 그대로 전달한다.
 */
public record KakaoLoginRequest(
        @NotBlank(message = "accessToken 은 필수입니다")
        String accessToken
) {}
