package com.example.pinq_backend.appconfig.dto;

import com.example.pinq_backend.config.properties.AppVersionProperties;

/**
 * 앱 실행 시 1회 조회하는 클라이언트 설정.
 *
 * 인증 없이 접근 가능해야 한다 — 로그인 화면에 닿기도 전에 구버전을 막아야 하고,
 * 서버 점검 공지도 로그인 실패보다 먼저 보여야 하기 때문이다.
 *
 * @param minVersionCode    versionCode 가 이 값 미만이면 클라이언트가 진행을 막는다
 * @param latestVersionCode 스토어 최신 버전 (선택 업데이트 안내용)
 * @param storeUrl          업데이트 버튼이 여는 주소
 * @param notice            공지 문구. null 이면 표시하지 않는다 (평시 기본값)
 */
public record AppConfigResponse(
    int minVersionCode,
    int latestVersionCode,
    String storeUrl,
    String notice
) {
    public static AppConfigResponse from(AppVersionProperties props) {
        String notice = props.notice();
        return new AppConfigResponse(
            props.minVersionCode(),
            props.latestVersionCode(),
            props.storeUrl(),
            // 빈 문자열은 "공지 없음"과 같은 뜻 — 프로퍼티 기본값이 빈 문자열이라 여기서 정규화한다
            (notice == null || notice.isBlank()) ? null : notice
        );
    }
}
