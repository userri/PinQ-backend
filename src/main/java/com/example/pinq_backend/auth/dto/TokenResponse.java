package com.example.pinq_backend.auth.dto;

/**
 * 로그인 성공 응답.
 *
 * Android 앱은 accessToken을 모든 API 호출에 사용하고,
 * 만료(401) 시 refreshToken으로 /api/auth/refresh를 호출해 재발급받는다.
 */
public record TokenResponse(
        Long userId,
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String nickname
) {
    public static TokenResponse of(Long userId, String accessToken, String refreshToken, long expiresIn, String nickname) {
        return new TokenResponse(userId, accessToken, refreshToken, "Bearer", expiresIn, nickname);
    }
}
