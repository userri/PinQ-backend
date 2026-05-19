package com.example.pinq_backend.auth.controller;

import com.example.pinq_backend.auth.dto.GoogleLoginRequest;
import com.example.pinq_backend.auth.dto.KakaoLoginRequest;
import com.example.pinq_backend.auth.dto.TokenResponse;
import com.example.pinq_backend.auth.service.GoogleOAuthService;
import com.example.pinq_backend.auth.service.GoogleOAuthService.GoogleOAuthException;
import com.example.pinq_backend.auth.service.GoogleOAuthService.GoogleUserInfo;
import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.auth.service.KakaoOAuthService;
import com.example.pinq_backend.auth.service.KakaoOAuthService.KakaoOAuthException;
import com.example.pinq_backend.auth.service.KakaoOAuthService.KakaoUserInfo;
import com.example.pinq_backend.auth.service.RefreshTokenService;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 소셜 로그인 엔드포인트.
 *
 * POST /api/auth/kakao   — Kakao accessToken → PinQ access + refresh token
 * POST /api/auth/google  — Google idToken    → PinQ access + refresh token
 * POST /api/auth/refresh — refresh token     → 새 access + refresh token (rotation)
 * POST /api/auth/logout  — refresh token 삭제 (서버 측 무효화)
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final KakaoOAuthService kakaoOAuthService;
    private final GoogleOAuthService googleOAuthService;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/kakao")
    public ResponseEntity<TokenResponse> kakaoLogin(
            @Valid @RequestBody KakaoLoginRequest request
    ) {
        KakaoUserInfo kakaoUser;
        try {
            kakaoUser = kakaoOAuthService.getUserInfo(request.accessToken());
        } catch (KakaoOAuthException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage(), e);
        }

        User user = userService.loginWithOAuth("kakao", kakaoUser.kakaoId(), kakaoUser.nickname());
        return ResponseEntity.ok(issueTokens(user));
    }

    @PostMapping("/google")
    public ResponseEntity<TokenResponse> googleLogin(
            @Valid @RequestBody GoogleLoginRequest request
    ) {
        GoogleUserInfo googleUser;
        try {
            googleUser = googleOAuthService.getUserInfo(request.idToken());
        } catch (GoogleOAuthException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage(), e);
        }

        User user = userService.loginWithOAuth("google", googleUser.googleId(), googleUser.nickname());
        return ResponseEntity.ok(issueTokens(user));
    }

    /**
     * Refresh token으로 새 access + refresh token을 발급한다 (rotation).
     *
     * @param request { "userId": 1, "refreshToken": "uuid-..." }
     * @return 200 OK + 새 TokenResponse  |  401 토큰 불일치/만료
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Valid @RequestBody RefreshRequest request
    ) {
        if (!refreshTokenService.validate(request.userId(), request.refreshToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token입니다");
        }

        User user = userService.findById(request.userId());

        // 기존 토큰 삭제 후 새 토큰 발급 (rotation)
        refreshTokenService.delete(request.userId());
        return ResponseEntity.ok(issueTokens(user));
    }

    /**
     * 로그아웃 — Redis의 refresh token을 삭제해 서버 측에서 무효화한다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.delete(request.userId());
        return ResponseEntity.noContent().build();
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private TokenResponse issueTokens(User user) {
        String accessToken  = jwtTokenProvider.createToken(user.getId(), user.getNickname());
        String refreshToken = refreshTokenService.issue(user.getId());
        return TokenResponse.of(user.getId(), accessToken, refreshToken, jwtTokenProvider.getExpirationMs(), user.getNickname());
    }

    // ── 요청 DTO ─────────────────────────────────────────────────────────────

    public record RefreshRequest(
            Long userId,
            @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(Long userId) {}
}
