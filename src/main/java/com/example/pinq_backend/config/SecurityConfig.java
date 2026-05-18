package com.example.pinq_backend.config;

import com.example.pinq_backend.auth.filter.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Phase 3 보안 설정.
 *
 *  - STATELESS: 세션 미사용, JWT 로만 인증.
 *  - /api/auth/**            : 로그인 — 인증 없이 접근 가능.
 *  - /api/users/register     : 회원가입 — 인증 없이 접근 가능.
 *  - /api/quizzes/**         : 퀴즈/채점 — JWT 없으면 demo 폴백 허용 (Phase 2 하위 호환).
 *  - /api/users/me/stats     : 통계 — demo 폴백 허용.
 *  - /api/users/me/**        : 닉네임 수정/탈퇴 — JWT 필수 (401).
 *  - /api/bookmarks/**       : 북마크 — JWT 필수 (401).
 *  - /api/me/**              : 풀이이력/오답노트 — JWT 필수 (401).
 *  - /api/admin/**           : AdminAuthFilter 가 X-Admin-Secret 헤더 검증.
 */
@Configuration
public class SecurityConfig {

    private final AdminAuthFilter adminAuthFilter;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(AdminAuthFilter adminAuthFilter, JwtAuthFilter jwtAuthFilter) {
        this.adminAuthFilter = adminAuthFilter;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 로그인/회원가입 — 인증 불필요
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("POST", "/api/users/register").permitAll()
                        // 개발/문서 도구
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/h2-console/**"
                        ).permitAll()
                        // JWT 필수 경로 — 미인증 시 401
                        .requestMatchers("/api/users/me/**").authenticated()
                        .requestMatchers("/api/bookmarks/**").authenticated()
                        .requestMatchers("/api/me/**").authenticated()
                        // 나머지(퀴즈/통계 등) — JWT 없으면 demo 폴백 허용
                        .anyRequest().permitAll()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(
                                HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
                ))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // JWT 파싱 → AdminAuthFilter 순서로 실행
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(adminAuthFilter, JwtAuthFilter.class)
                .build();
    }
}