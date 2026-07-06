package com.example.pinq_backend.auth;

import com.example.pinq_backend.user.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext 에서 현재 인증된 사용자 ID 를 꺼내는 유틸리티.
 *
 * JWT 필터가 토큰을 파싱하면 principal 에 userId(Long) 를 저장한다.
 * 토큰이 없으면 demo 유저 ID 로 폴백(Phase 2 하위 호환).
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * 현재 요청의 userId 를 반환한다.
     *
     * JWT 인증이 되어 있으면 토큰의 userId,
     * 그렇지 않으면 demo 유저 ID 를 반환한다.
     */
    public static Long getCurrentUserId(UserService userService) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        // SecurityConfig 가 모든 API 에 JWT 를 요구하므로 운영 요청은 여기 도달하지 않는다.
        // @WebMvcTest 등 테스트 환경(principal 이 Long 이 아닌 경우)의 폴백으로만 쓰인다.
        return userService.findDemoUser().getId();
    }
}
