package com.example.pinq_backend.config;

import com.example.pinq_backend.auth.filter.JwtAuthFilter;
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
 *  - /api/auth/**  : 로그인 엔드포인트 — 인증 없이 접근 가능.
 *  - /api/**       : JWT 가 있으면 해당 유저, 없으면 demo 유저로 폴백 (Phase 2 하위 호환).
 *  - /api/admin/** : AdminAuthFilter 가 X-Admin-Secret 헤더 검증.
 *  - JwtAuthFilter : UsernamePasswordAuthenticationFilter 앞에서 Bearer 토큰 파싱.
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
                        // 로그인 엔드포인트는 인증 없이 오픈
                        .requestMatchers("/api/auth/**").permitAll()
                        // 개발/문서 도구
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/h2-console/**"
                        ).permitAll()
                        // 나머지 모든 요청도 permitAll — JWT 없으면 demo 유저 폴백
                        .anyRequest().permitAll()
                )
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // JWT 파싱 → AdminAuthFilter 순서로 실행
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(adminAuthFilter, JwtAuthFilter.class)
                .build();
    }
}