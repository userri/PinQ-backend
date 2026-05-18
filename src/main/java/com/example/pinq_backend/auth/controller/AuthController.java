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
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.service.UserService;
import jakarta.validation.Valid;
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
 * POST /api/auth/kakao  — Kakao accessToken → PinQ JWT
 * POST /api/auth/google — Google idToken   → PinQ JWT
 *
 * 두 엔드포인트 모두 인증 없이 접근 가능 (SecurityConfig 에서 permitAll).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final KakaoOAuthService kakaoOAuthService;
    private final GoogleOAuthService googleOAuthService;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 카카오 로그인.
     *
     * @param request { "accessToken": "..." } — Kakao SDK 발급 액세스 토큰
     * @return 200 OK + { accessToken, tokenType, expiresIn, nickname }
     *         401 Unauthorized — 카카오 토큰 검증 실패
     */
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
        String jwt = jwtTokenProvider.createToken(user.getId(), user.getNickname());

        return ResponseEntity.ok(
                TokenResponse.of(jwt, jwtTokenProvider.getExpirationMs(), user.getNickname())
        );
    }

    /**
     * 구글 로그인.
     *
     * @param request { "idToken": "..." } — Google Credential Manager 발급 ID Token
     * @return 200 OK + { accessToken, tokenType, expiresIn, nickname }
     *         401 Unauthorized — 구글 토큰 검증 실패
     */
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
        String jwt = jwtTokenProvider.createToken(user.getId(), user.getNickname());

        return ResponseEntity.ok(
                TokenResponse.of(jwt, jwtTokenProvider.getExpirationMs(), user.getNickname())
        );
    }
}
