package com.example.pinq_backend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 구글 OAuth — ID Token 을 검증하고 사용자 정보를 추출한다.
 *
 * 흐름:
 *  1. Android 앱이 Credential Manager 로 Google Sign-In → ID Token 획득
 *  2. 앱이 idToken 을 백엔드로 전달
 *  3. 백엔드가 Google tokeninfo 엔드포인트로 ID Token 검증 · 정보 조회
 *
 * 운영 개선: 구글의 공개 키로 직접 JWT 서명 검증하면 더 안전하다.
 * 현재는 개발 편의상 tokeninfo API 를 사용한다.
 */
@Service
@Slf4j
public class GoogleOAuthService {

    private static final String GOOGLE_TOKENINFO_URL =
            "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Google ID Token 을 검증하고 사용자 정보를 반환한다.
     *
     * @param idToken Android Credential Manager 에서 받은 Google ID Token
     * @return 구글 사용자 정보
     * @throws GoogleOAuthException 토큰이 유효하지 않거나 API 호출 실패 시
     */
    public GoogleUserInfo getUserInfo(String idToken) {
        try {
            GoogleTokenInfo info = restTemplate.getForObject(
                    GOOGLE_TOKENINFO_URL + idToken,
                    GoogleTokenInfo.class
            );

            if (info == null || info.sub() == null) {
                throw new GoogleOAuthException("구글 토큰 정보가 비어 있습니다.");
            }
            if (info.errorDescription() != null) {
                throw new GoogleOAuthException("구글 토큰 검증 실패: " + info.errorDescription());
            }

            String nickname = info.name() != null ? info.name()
                    : (info.email() != null ? info.email().split("@")[0] : "구글유저");

            return new GoogleUserInfo(info.sub(), nickname);

        } catch (RestClientException e) {
            log.warn("구글 tokeninfo API 호출 실패: {}", e.getMessage());
            throw new GoogleOAuthException("구글 토큰 검증에 실패했습니다.", e);
        }
    }

    // ── 구글 API 응답 모델 ────────────────────────────────────────────────────

    public record GoogleUserInfo(String googleId, String nickname) {}

    private record GoogleTokenInfo(
            String sub,
            String email,
            String name,
            @JsonProperty("error_description") String errorDescription
    ) {}

    // ── 예외 ─────────────────────────────────────────────────────────────────

    public static class GoogleOAuthException extends RuntimeException {
        public GoogleOAuthException(String message) { super(message); }
        public GoogleOAuthException(String message, Throwable cause) { super(message, cause); }
    }
}
