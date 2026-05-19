package com.example.pinq_backend.auth.filter;

import com.example.pinq_backend.auth.service.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT Bearer 토큰을 파싱하여 SecurityContext 에 userId 를 설정하는 필터.
 *
 * Authorization: Bearer <token> 헤더가 있고 토큰이 유효하면
 * UsernamePasswordAuthenticationToken(userId, null, []) 을 SecurityContext 에 등록한다.
 *
 * Authorization 헤더가 없으면 다음 필터로 넘겨 SecurityConfig 의 permitAll/demo fallback
 * 정책을 따르게 한다. 반대로 Bearer 토큰이 있지만 유효하지 않으면 401 을 반환해
 * 만료·위조 토큰 요청이 demo 유저로 처리되지 않도록 차단한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null) {
            try {
                Long userId = jwtTokenProvider.getUserId(token);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.debug("JWT 파싱 오류: {}", e.getMessage());
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /** Authorization: Bearer <token> 에서 토큰 문자열을 추출한다. */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
