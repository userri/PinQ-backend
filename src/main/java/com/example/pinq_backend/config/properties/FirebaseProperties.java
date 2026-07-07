package com.example.pinq_backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Firebase Admin SDK 설정 (FCM 푸시 전송용).
 *
 * serviceAccountBase64: 서비스 계정 키(JSON)를 base64 로 인코딩한 문자열.
 *   운영: Firebase 콘솔 > 프로젝트 설정 > 서비스 계정 > 새 비공개 키 생성 →
 *        받은 JSON 을 base64 로 인코딩해 환경변수 FIREBASE_SERVICE_ACCOUNT_BASE64 로 주입.
 *        (파일 경로 대신 base64 를 쓰는 이유: 컨테이너에 키 파일을 두지 않아도 되고
 *         blue/green 배포에서 시크릿 주입이 단순해짐)
 *   미설정(빈 문자열) 시: FCM 전송이 비활성화되지만 앱은 정상 기동한다
 *        (FcmPushClient 가 no-op 으로 처리 — AnthropicVerifyClient 의 fail-open 과 동일 철학).
 */
@ConfigurationProperties(prefix = "firebase")
public record FirebaseProperties(
    String serviceAccountBase64
) {}
