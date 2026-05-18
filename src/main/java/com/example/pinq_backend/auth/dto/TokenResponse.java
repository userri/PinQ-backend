package com.example.pinq_backend.auth.dto;

/**
 * 로그인 성공 응답.
 * Android 앱은 accessToken 을 저장해 이후 모든 API 호출에 사용한다.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String nickname
) {
    public static TokenResponse of(String accessToken, long expiresIn, String nickname) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, nickname);
    }
}
