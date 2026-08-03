package com.example.pinq_backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 클라이언트 버전 게이트 설정.
 *
 * 존재 이유: 구버전 앱을 막는 검사는 <b>그 구버전 안에 이미 들어 있어야</b> 동작한다.
 * 필요해진 시점에 만들면 이미 배포된 버전에는 영원히 적용할 수 없으므로,
 * 아직 막을 일이 없을 때 미리 심어둔다.
 *
 * 값은 프로퍼티라서 <b>백엔드 재배포만으로</b> 바꿀 수 있다 — 앱 심사를 기다릴 필요가 없다.
 *
 * @param minVersionCode    이 값 미만이면 앱이 차단 다이얼로그를 띄운다.
 *                          평시에는 실제 배포 버전보다 낮게 둬서 아무도 막히지 않게 한다.
 * @param latestVersionCode 현재 스토어 최신 버전. 클라이언트가 "업데이트 있음"을 판단하는 용도.
 * @param storeUrl          업데이트 버튼이 여는 주소.
 * @param notice            앱 실행 시 1회 노출할 공지. 없으면 null/빈 문자열 — 평시 기본값.
 *                          점검·변경 안내를 앱 배포 없이 띄우는 창구다.
 */
@ConfigurationProperties(prefix = "app.version")
public record AppVersionProperties(
    int minVersionCode,
    int latestVersionCode,
    String storeUrl,
    String notice
) {}
