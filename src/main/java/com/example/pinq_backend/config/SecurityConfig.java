package com.example.pinq_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 1 보안 설정.
 *
 *  - REST API 만 제공하므로 세션 사용 안 함 (STATELESS).
 *  - CSRF 비활성화: 토큰 없이도 POST 가능 (Phase 1 인증 도입 전).
 *  - 소셜 로그인은 Phase 2 에서 OAuth2 클라이언트로 별도 추가 예정.
 */
@Configuration
public class SecurityConfig {

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
                .anyRequest().permitAll() // Phase 1: 전체 오픈. Phase 2 에서 좁힐 것.
            )
            // H2 콘솔은 iframe 으로 렌더되므로 X-Frame-Options 완화 필요.
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .build();
    }
}
