package com.example.pinq_backend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 카카오 OAuth — accessToken 으로 사용자 정보를 조회한다.
 *
 * 흐름:
 *  1. Android 앱이 Kakao SDK 로 로그인 → accessToken 획득
 *  2. 앱이 accessToken 을 백엔드로 전달
 *  3. 백엔드가 Kakao API 에 accessToken 을 제출해 사용자 정보 검증 · 조회
 */
@Service
@Slf4j
public class KakaoOAuthService {

    private static final String KAKAO_USER_ME_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Kakao accessToken 으로 사용자 정보를 조회한다.
     *
     * @param accessToken Kakao SDK 에서 발급된 access token
     * @return 카카오 사용자 정보
     * @throws KakaoOAuthException 토큰이 유효하지 않거나 API 호출 실패 시
     */
    public KakaoUserInfo getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<KakaoApiResponse> response = restTemplate.exchange(
                    KAKAO_USER_ME_URL, HttpMethod.GET, request, KakaoApiResponse.class);

            KakaoApiResponse body = response.getBody();
            if (body == null || body.id() == null) {
                throw new KakaoOAuthException("카카오 API 응답이 비어 있습니다.");
            }

            String nickname = extractNickname(body);
            return new KakaoUserInfo(body.id().toString(), nickname);

        } catch (RestClientException e) {
            log.warn("카카오 API 호출 실패: {}", e.getMessage());
            throw new KakaoOAuthException("카카오 토큰 검증에 실패했습니다.", e);
        }
    }

    private String extractNickname(KakaoApiResponse body) {
        if (body.kakaoAccount() != null
                && body.kakaoAccount().profile() != null
                && body.kakaoAccount().profile().nickname() != null) {
            return body.kakaoAccount().profile().nickname();
        }
        if (body.properties() != null && body.properties().nickname() != null) {
            return body.properties().nickname();
        }
        int randomNum = ThreadLocalRandom.current().nextInt(100000, 1000000); // 6자리
        return "유저" + randomNum;
    }

    // ── 카카오 API 응답 모델 ──────────────────────────────────────────────────

    public record KakaoUserInfo(String kakaoId, String nickname) {}

    private record KakaoApiResponse(
            Long id,
            @JsonProperty("kakao_account") KakaoAccount kakaoAccount,
            KakaoProperties properties
    ) {}

    private record KakaoAccount(
            KakaoProfile profile
    ) {}

    private record KakaoProfile(
            String nickname
    ) {}

    private record KakaoProperties(
            String nickname
    ) {}

    // ── 예외 ─────────────────────────────────────────────────────────────────

    public static class KakaoOAuthException extends RuntimeException {
        public KakaoOAuthException(String message) { super(message); }
        public KakaoOAuthException(String message, Throwable cause) { super(message, cause); }
    }
}
