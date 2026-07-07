package com.example.pinq_backend.user.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 사용자.
 *
 * OAuth 관련 필드(oauth_provider, oauth_id) 는 Phase 2 에선 NULL 로 두고,
 * Phase 3 에서 Google OAuth 도입 시 실제 값으로 채운다.
 * Phase 2 운영은 nickname="demo" 인 anonymous user 1명으로 진행.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_oauth",
                        columnNames = {"oauth_provider", "oauth_id"}
                ),
                @UniqueConstraint(
                        name = "uk_user_nickname",
                        columnNames = {"nickname"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Phase 3 도입 예정 — 지금은 NULL 허용
    @Column(name = "oauth_provider", length = 20)
    private String oauthProvider;

    @Column(name = "oauth_id", length = 100)
    private String oauthId;

    @Column(name = "nickname", nullable = false, length = 30)
    private String nickname;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    @Column(name = "max_streak", nullable = false)
    private int maxStreak;

    @Column(name = "last_solved_date")
    private LocalDate lastSolvedDate;

    /** 데일리 퀴즈 알림 수신 여부. 기본 false (사용자가 명시적으로 켜야 함). */
    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    /**
     * 알림 수신 희망 시각 (KST). 30분 단위(분은 0 또는 30)만 허용한다.
     * 스케줄러가 30분 슬롯마다 이 값과 일치하는 사용자에게 전송한다.
     */
    @Column(name = "notification_time")
    private LocalTime notificationTime;

    @Builder
    private User(
            String oauthProvider,
            String oauthId,
            String nickname,
            int currentStreak,
            int maxStreak,
            LocalDate lastSolvedDate
    ) {
        this.oauthProvider = oauthProvider;
        this.oauthId = oauthId;
        this.nickname = nickname;
        this.currentStreak = currentStreak;
        this.maxStreak = maxStreak;
        this.lastSolvedDate = lastSolvedDate;
        this.notificationEnabled = false;
        this.notificationTime = DEFAULT_NOTIFICATION_TIME;
    }

    /** 기본 알림 시각 09:00 (KST). */
    public static final LocalTime DEFAULT_NOTIFICATION_TIME = LocalTime.of(9, 0);

    /**
     * 오늘 퀴즈 완주 시 호출.
     * 어제 풀었다면 streak 증가, 더 비었다면 1로 리셋, 오늘 이미 풀었으면 변화 없음.
     */
    public void recordSolvedOn(LocalDate today) {
        if (lastSolvedDate != null && lastSolvedDate.isEqual(today)) {
            return;
        }
        currentStreak = computeNextStreak(today);
        if (currentStreak > maxStreak) {
            maxStreak = currentStreak;
        }
        lastSolvedDate = today;
    }

    private int computeNextStreak(LocalDate today) {
        if (lastSolvedDate == null) {
            return 1;
        }
        boolean isConsecutive = lastSolvedDate.plusDays(1).isEqual(today);
        return isConsecutive ? currentStreak + 1 : 1;
    }

    /** 닉네임 수정. */
    public void updateNickname(String newNickname) {
        this.nickname = newNickname;
    }

    /**
     * 알림 설정 변경.
     *
     * @param enabled 알림 수신 여부
     * @param time    수신 희망 시각. null 이면 기존 시각 유지.
     *                30분 단위(분이 0 또는 30)가 아니면 거부한다 — 스케줄러가
     *                30분 슬롯으로만 스캔하므로 그 외 값은 영원히 발송되지 않는다.
     */
    public void updateNotificationSettings(boolean enabled, java.time.LocalTime time) {
        if (time != null) {
            if (time.getMinute() % 30 != 0 || time.getSecond() != 0 || time.getNano() != 0) {
                throw new IllegalArgumentException(
                        "알림 시각은 30분 단위(HH:00 또는 HH:30)여야 합니다: " + time);
            }
            this.notificationTime = time;
        }
        this.notificationEnabled = enabled;
    }

    /** 풀이 이력에서 재계산한 streak 값을 users 테이블 캐시 필드에 반영한다. */
    public void syncStreak(int currentStreak, int maxStreak, LocalDate lastSolvedDate) {
        this.currentStreak = Math.max(currentStreak, 0);
        this.maxStreak = Math.max(maxStreak, this.currentStreak);
        this.lastSolvedDate = lastSolvedDate;
    }
}
