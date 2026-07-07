package com.example.pinq_backend.notification.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import com.example.pinq_backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 FCM 디바이스 토큰.
 *
 * 정책:
 *  - 한 사용자가 여러 기기를 가질 수 있다 (user 1 : N token).
 *  - 토큰은 전역 유니크 — 같은 기기에서 다른 계정으로 로그인하면
 *    토큰의 소유자를 새 계정으로 옮긴다 (registerToken 에서 기존 행 삭제 후 재등록).
 *  - FCM 이 UNREGISTERED 응답을 주면(앱 삭제·토큰 만료) 해당 행을 삭제한다.
 */
@Entity
@Table(
    name = "user_device_token",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_device_token",
        columnNames = {"token"}
    ),
    indexes = @Index(name = "idx_device_token_user", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDeviceToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** FCM registration token. 통상 150~170자이나 상한 명세가 없어 여유 있게 512. */
    @Column(name = "token", nullable = false, length = 512)
    private String token;

    public static UserDeviceToken create(User user, String token) {
        UserDeviceToken t = new UserDeviceToken();
        t.user = user;
        t.token = token;
        return t;
    }
}
