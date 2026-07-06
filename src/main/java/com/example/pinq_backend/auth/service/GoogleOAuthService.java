package com.example.pinq_backend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 구글 OAuth — ID Token 을 검증하고 사용자 정보를 추출한다.
 *
 * 흐름:
 *  1. Android 앱이 Credential Manager 로 Google Sign-In → ID Token 획득
 *  2. 앱이 idToken 을 백엔드로 전달
 *  3. 백엔드가 Google tokeninfo 엔드포인트로 ID Token 검증 · 정보 조회
 *  4. aud 클레임이 자사 웹 클라이언트 ID 와 일치하는지 확인
 *
 * aud 검증이 없으면 다른 앱(공격자의 클라이언트 ID)으로 발급된 피해자의
 * ID Token 을 재전송해 그 사용자로 로그인하는 토큰 치환 공격이 가능하다.
 *
 * 운영 개선: 구글의 공개 키로 직접 JWT 서명 검증하면 tokeninfo 왕복을 없앨 수 있다.
 */
@Service
@Slf4j
public class GoogleOAuthService {

    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";

    private final RestTemplate restTemplate;
    private final String expectedClientId;

    public GoogleOAuthService(@Value("${google.web-client-id:}") String expectedClientId) {
        // 구글 API 지연 시 로그인 스레드가 무한 대기하지 않도록 타임아웃을 명시한다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
        this.expectedClientId = expectedClientId;
        if (expectedClientId.isBlank()) {
            log.error("google.web-client-id 미설정 — aud 검증이 불가능하므로 구글 로그인이 전부 거부됩니다. "
                    + "GOOGLE_WEB_CLIENT_ID 환경변수를 설정하세요 (Android 앱의 웹 클라이언트 ID 와 동일 값).");
        }
    }

    /**
     * Google ID Token 을 검증하고 사용자 정보를 반환한다.
     *
     * @param idToken Android Credential Manager 에서 받은 Google ID Token
     * @return 구글 사용자 정보
     * @throws GoogleOAuthException 토큰이 유효하지 않거나, aud 가 자사 클라이언트 ID 와
     *         다르거나, API 호출 실패 시
     */
    public GoogleUserInfo getUserInfo(String idToken) {
        if (expectedClientId.isBlank()) {
            throw new GoogleOAuthException("서버에 구글 클라이언트 ID 가 설정되지 않았습니다.");
        }
        try {
            // 토큰을 URL 쿼리가 아닌 POST 폼 바디로 전달 — 프록시/액세스 로그에 토큰이 남지 않도록.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("id_token", idToken);

            GoogleTokenInfo info = restTemplate.postForObject(
                    GOOGLE_TOKENINFO_URL,
                    new HttpEntity<>(form, headers),
                    GoogleTokenInfo.class
            );

            if (info == null || info.sub() == null) {
                throw new GoogleOAuthException("구글 토큰 정보가 비어 있습니다.");
            }
            if (info.errorDescription() != null) {
                throw new GoogleOAuthException("구글 토큰 검증 실패: " + info.errorDescription());
            }
            // aud 검증 — 이 토큰이 우리 앱(자사 클라이언트 ID)에게 발급된 것인지 확인.
            if (!expectedClientId.equals(info.aud())) {
                log.warn("구글 ID Token aud 불일치 — 다른 클라이언트로 발급된 토큰 거부: aud={}", info.aud());
                throw new GoogleOAuthException("이 앱에서 발급된 구글 토큰이 아닙니다.");
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
            String aud,
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
