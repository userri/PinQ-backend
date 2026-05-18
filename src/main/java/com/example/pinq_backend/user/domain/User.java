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
    }

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
}