package com.example.pinq_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Phase 2 보안 설정.
 *
 *  - REST API 만 제공하므로 세션 사용 안 함 (STATELESS).
 *  - CSRF 비활성화: 토큰 없이도 POST 가능 (Phase 2 인증 도입 전).
 *  - /api/admin/** 는 AdminAuthFilter 가 X-Admin-Secret 헤더를 검증한다.
 *  - 소셜 로그인은 Phase 3 에서 OAuth2 클라이언트로 별도 추가 예정.
 */
@Configuration
public class SecurityConfig {

    private final AdminAuthFilter adminAuthFilter;

    public SecurityConfig(AdminAuthFilter adminAuthFilter) {
        this.adminAuthFilter = adminAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/h2-console/**"
                        ).permitAll()
                        .anyRequest().permitAll() // Phase 2: 전체 오픈. Phase 3 에서 좁힐 것.
                )
                // H2 콘솔은 iframe 으로 렌더되므로 X-Frame-Options 완화 필요.
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // AdminAuthFilter 는 Spring Security 인증 필터 앞에서 실행된다.
                // shouldNotFilter() 로 /api/admin/** 경로에만 적용.
                .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}