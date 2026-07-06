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
 * 보안 설정.
 *
 *  - STATELESS: 세션 미사용, JWT 로만 인증.
 *  - /api/auth/**            : 로그인/토큰 재발급 — 인증 없이 접근 가능.
 *  - /api/admin/**           : AdminAuthFilter 가 X-Admin-Secret 헤더 검증.
 *  - 그 외 /api/**           : JWT 필수 (401).
 *
 * Phase 2 시절의 demo 폴백(비인증 요청을 demo 유저로 처리)과 공개 회원가입은
 * 카카오/구글 로그인 도입으로 더 이상 필요 없어 출시 전에 잠갔다.
 * 열어두면 인터넷의 누구나 채점 기록을 오염시키고 무제한 회원 생성이 가능했다.
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
                        // 로그인/토큰 재발급 — 인증 불필요
                        .requestMatchers("/api/auth/**").permitAll()
                        // 개발/문서 도구 — swagger 문서는 prod 프로파일에서
                        // springdoc.*.enabled=false 로 엔드포인트 자체가 꺼진다
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/h2-console/**"
                        ).permitAll()
                        // 어드민 — 인증은 AdminAuthFilter 의 X-Admin-Secret 검증이 담당
                        .requestMatchers("/api/admin/**").permitAll()
                        // 나머지 API 전부 JWT 필수 — 미인증 시 401
                        .anyRequest().authenticated()
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
