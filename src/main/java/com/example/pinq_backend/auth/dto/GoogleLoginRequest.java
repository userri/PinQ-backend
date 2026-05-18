package com.example.pinq_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 구글 로그인 요청.
 * Android 앱이 Credential Manager 에서 받은 Google ID Token 을 전달한다.
 */
public record GoogleLoginRequest(
        @NotBlank(message = "idToken 은 필수입니다")
        String idToken
) {}
