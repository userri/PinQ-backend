package com.example.pinq_backend.appconfig.controller;

import com.example.pinq_backend.appconfig.dto.AppConfigResponse;
import com.example.pinq_backend.config.properties.AppVersionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 클라이언트 부트스트랩 설정.
 *
 *  GET /api/app/config : 최소 지원 버전 · 최신 버전 · 공지
 *
 * 인증 없이 열려 있다(SecurityConfig). 로그인 이전에 구버전을 막아야 하고,
 * 사용자 데이터를 일절 담지 않으므로 공개해도 노출되는 정보가 없다.
 */
@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppConfigController {

    private final AppVersionProperties appVersionProperties;

    @GetMapping("/config")
    public AppConfigResponse getConfig() {
        return AppConfigResponse.from(appVersionProperties);
    }
}
