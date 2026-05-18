package com.example.pinq_backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pinq_backend.auth.service.GoogleOAuthService;
import com.example.pinq_backend.auth.service.GoogleOAuthService.GoogleOAuthException;
import com.example.pinq_backend.auth.service.GoogleOAuthService.GoogleUserInfo;
import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.auth.service.KakaoOAuthService;
import com.example.pinq_backend.auth.service.KakaoOAuthService.KakaoOAuthException;
import com.example.pinq_backend.auth.service.KakaoOAuthService.KakaoUserInfo;
import com.example.pinq_backend.quiz.exception.GlobalExceptionHandler;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AuthController HTTP 계층 검증.
 *
 *  1. Kakao 로그인 성공 → 200 + 토큰 shape
 *  2. Kakao 토큰 검증 실패 → 401
 *  3. Google 로그인 성공 → 200 + 토큰 shape
 *  4. Google 토큰 검증 실패 → 401
 *  5. 요청 body 누락(validation) → 400
 */
@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
@WithMockUser
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KakaoOAuthService kakaoOAuthService;

    @MockitoBean
    private GoogleOAuthService googleOAuthService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    private User mockUser;

    @BeforeEach
    void setUp() {
        mockUser = mock(User.class);
        given(mockUser.getId()).willReturn(1L);
        given(mockUser.getNickname()).willReturn("테스트유저");
        given(userService.loginWithOAuth(anyString(), anyString(), anyString()))
            .willReturn(mockUser);
        given(jwtTokenProvider.createToken(anyLong(), anyString()))
            .willReturn("test.jwt.token");
        given(jwtTokenProvider.getExpirationMs()).willReturn(3_600_000L);
    }

    @Test
    @DisplayName("카카오 로그인 성공 시 200 과 토큰을 반환한다")
    void kakaoLogin_success() throws Exception {
        KakaoUserInfo kakaoUser = mock(KakaoUserInfo.class);
        given(kakaoUser.kakaoId()).willReturn("kakao-123");
        given(kakaoUser.nickname()).willReturn("테스트유저");
        given(kakaoOAuthService.getUserInfo(any())).willReturn(kakaoUser);

        mockMvc.perform(post("/api/auth/kakao")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\": \"valid-kakao-token\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("test.jwt.token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(3600000))
            .andExpect(jsonPath("$.nickname").value("테스트유저"));
    }

    @Test
    @DisplayName("카카오 토큰 검증 실패 시 401 을 반환한다")
    void kakaoLogin_invalidToken_returns401() throws Exception {
        given(kakaoOAuthService.getUserInfo(any()))
            .willThrow(new KakaoOAuthException("카카오 인증 실패"));

        mockMvc.perform(post("/api/auth/kakao")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\": \"invalid-token\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("구글 로그인 성공 시 200 과 토큰을 반환한다")
    void googleLogin_success() throws Exception {
        GoogleUserInfo googleUser = mock(GoogleUserInfo.class);
        given(googleUser.googleId()).willReturn("google-456");
        given(googleUser.nickname()).willReturn("테스트유저");
        given(googleOAuthService.getUserInfo(any())).willReturn(googleUser);

        mockMvc.perform(post("/api/auth/google")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\": \"valid-google-token\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("test.jwt.token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("구글 토큰 검증 실패 시 401 을 반환한다")
    void googleLogin_invalidToken_returns401() throws Exception {
        given(googleOAuthService.getUserInfo(any()))
            .willThrow(new GoogleOAuthException("구글 인증 실패"));

        mockMvc.perform(post("/api/auth/google")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\": \"invalid-token\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("요청 body 가 비어 있으면 400 을 반환한다")
    void kakaoLogin_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }
}
