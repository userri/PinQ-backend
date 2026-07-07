package com.example.pinq_backend.notification.client;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * FCM 푸시 전송 클라이언트.
 *
 * FirebaseMessaging 을 ObjectProvider 로 받는 이유:
 *  FirebaseConfig 는 서비스 계정 키 미설정 시 null 빈을 등록하는데, 일반 생성자
 *  주입은 null 빈을 "필수 의존성 미충족"으로 취급해 앱 기동이 실패한다.
 *  ObjectProvider.getIfAvailable() 은 이 경우 null 을 돌려주므로
 *  키 없이도 앱이 뜨고, 전송만 no-op(DISABLED) 이 된다.
 */
@Slf4j
@Component
public class FcmPushClient {

    /** 개별 토큰 전송 결과. */
    public enum SendResult {
        /** 전송 성공 */
        SENT,
        /** 토큰이 더 이상 유효하지 않음 (앱 삭제·토큰 회전) — 호출자가 토큰을 삭제해야 함 */
        INVALID_TOKEN,
        /** 일시적 실패 (네트워크·FCM 장애) — 토큰은 유지 */
        ERROR,
        /** Firebase 미설정으로 전송 비활성화 상태 */
        DISABLED,
    }

    private final FirebaseMessaging firebaseMessaging;

    public FcmPushClient(ObjectProvider<FirebaseMessaging> firebaseMessagingProvider) {
        this.firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
    }

    /**
     * 단일 디바이스 토큰으로 알림을 전송한다.
     *
     * @return 전송 결과. INVALID_TOKEN 이면 호출자가 해당 토큰을 정리해야 한다.
     */
    public SendResult send(String token, String title, String body) {
        if (firebaseMessaging == null) {
            return SendResult.DISABLED;
        }

        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            firebaseMessaging.send(message);
            return SendResult.SENT;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            // UNREGISTERED: 앱 삭제·토큰 만료. INVALID_ARGUMENT: 형식이 깨진 토큰.
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("FCM 토큰 무효 ({}). 토큰 정리 대상. token={}...", code, safePrefix(token));
                return SendResult.INVALID_TOKEN;
            }
            log.warn("FCM 전송 실패 ({}). token={}...", code, safePrefix(token), e);
            return SendResult.ERROR;
        } catch (Exception e) {
            log.warn("FCM 전송 중 예기치 못한 오류. token={}...", safePrefix(token), e);
            return SendResult.ERROR;
        }
    }

    /** 로그에 토큰 전체를 남기지 않기 위한 앞 12자 프리픽스. */
    private String safePrefix(String token) {
        if (token == null) return "null";
        return token.length() <= 12 ? token : token.substring(0, 12);
    }
}
