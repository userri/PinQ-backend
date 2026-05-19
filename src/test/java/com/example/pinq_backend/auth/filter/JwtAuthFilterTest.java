package com.example.pinq_backend.auth.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.pinq_backend.auth.service.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("잘못된 Bearer 토큰은 demo fallback 으로 넘기지 않고 401로 차단한다")
    void invalidBearerToken_returnsUnauthorized() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtTokenProvider);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        given(jwtTokenProvider.getUserId("invalid-token"))
            .willThrow(new IllegalArgumentException("invalid"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("토큰이 없으면 기존 Phase 2 demo fallback 경로를 유지한다")
    void noToken_continuesFilterChain() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtTokenProvider);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        verifyNoInteractions(jwtTokenProvider);
    }
}
