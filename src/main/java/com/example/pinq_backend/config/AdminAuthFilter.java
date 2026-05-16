package com.example.pinq_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/admin/** 경로에 대한 secret key 인증 필터.
 *
 * Phase 2 임시 방어선 — OAuth 도입(Phase 3) 시 제거 예정.
 *
 * 요청 헤더에 X-Admin-Secret: <값> 이 없거나 환경변수 ADMIN_SECRET 과 일치하지 않으면
 * 401 Unauthorized 를 반환하고 필터 체인을 중단한다.
 *
 * ADMIN_SECRET 이 설정되지 않은 경우(빈 문자열) admin 엔드포인트는 항상 차단된다.
 * 운영 환경에서는 반드시 충분히 긴 무작위 문자열로 설정해야 한다.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    private final String adminSecret;

    public AdminAuthFilter(@Value("${admin.secret:}") String adminSecret) {
        this.adminSecret = adminSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ADMIN_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String provided = request.getHeader(ADMIN_SECRET_HEADER);

        // secret 미설정이거나 헤더 불일치 시 차단
        if (adminSecret.isBlank() || !adminSecret.equals(provided)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Unauthorized\",\"status\":401}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}