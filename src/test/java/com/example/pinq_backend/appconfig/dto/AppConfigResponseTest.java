package com.example.pinq_backend.appconfig.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.config.properties.AppVersionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppConfigResponseTest {

    private static final String STORE_URL = "https://play.google.com/store/apps/details?id=com.finq.app";

    @Test
    @DisplayName("공지가 비어 있으면 null 로 정규화한다 — 프로퍼티 기본값이 빈 문자열이라")
    void blankNotice_becomesNull() {
        var response = AppConfigResponse.from(new AppVersionProperties(1, 12, STORE_URL, ""));

        assertThat(response.notice()).isNull();
        assertThat(response.minVersionCode()).isEqualTo(1);
        assertThat(response.latestVersionCode()).isEqualTo(12);
        assertThat(response.storeUrl()).isEqualTo(STORE_URL);
    }

    @Test
    @DisplayName("공지가 공백만 있어도 null — 클라이언트가 빈 배너를 띄우지 않게")
    void whitespaceNotice_becomesNull() {
        var response = AppConfigResponse.from(new AppVersionProperties(1, 12, STORE_URL, "   "));

        assertThat(response.notice()).isNull();
    }

    @Test
    @DisplayName("공지가 있으면 그대로 전달한다")
    void notice_passedThrough() {
        var response = AppConfigResponse.from(
                new AppVersionProperties(1, 12, STORE_URL, "오늘 02:00~03:00 점검 예정입니다"));

        assertThat(response.notice()).isEqualTo("오늘 02:00~03:00 점검 예정입니다");
    }

    @Test
    @DisplayName("notice 가 null 이어도 안전하다")
    void nullNotice_stayNull() {
        var response = AppConfigResponse.from(new AppVersionProperties(13, 13, STORE_URL, null));

        assertThat(response.notice()).isNull();
        assertThat(response.minVersionCode()).isEqualTo(13);
    }
}
