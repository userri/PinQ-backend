package com.example.pinq_backend.config;

import com.example.pinq_backend.config.properties.FirebaseProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase Admin SDK 초기화.
 *
 * FirebaseMessaging 빈을 항상 등록하되, 서비스 계정 키가 없거나 초기화에 실패하면
 * null 빈을 등록한다. FcmPushClient 는 null 을 받으면 no-op 으로 동작해
 * 키가 없어도 앱이 정상 기동한다 (로컬·CI 환경 배려).
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    private static final String APP_NAME = "pinq";

    /**
     * FirebaseMessaging 빈. 키 미설정/초기화 실패 시 null 을 반환한다.
     * (@Bean 이 null 을 반환하면 해당 타입 주입 지점에 null 이 주입되며,
     *  FcmPushClient 는 이를 명시적으로 처리한다.)
     */
    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseProperties props) {
        String base64 = props.serviceAccountBase64();
        if (base64 == null || base64.isBlank()) {
            log.warn("[FirebaseConfig] FIREBASE_SERVICE_ACCOUNT_BASE64 미설정 — "
                    + "FCM 푸시 전송이 비활성화됩니다 (앱 기동에는 영향 없음).");
            return null;
        }

        try {
            byte[] json = Base64.getDecoder().decode(base64.trim().getBytes(StandardCharsets.UTF_8));
            GoogleCredentials credentials =
                    GoogleCredentials.fromStream(new ByteArrayInputStream(json));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            // 이미 초기화된 앱이 있으면 재사용 (테스트/재시작 시 중복 초기화 방지)
            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(a -> APP_NAME.equals(a.getName()))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));

            log.info("[FirebaseConfig] Firebase Admin SDK 초기화 완료. FCM 푸시 전송 활성화.");
            return FirebaseMessaging.getInstance(app);
        } catch (Exception e) {
            log.error("[FirebaseConfig] Firebase 초기화 실패 — FCM 전송 비활성화. "
                    + "서비스 계정 키(base64) 를 확인하세요.", e);
            return null;
        }
    }
}
