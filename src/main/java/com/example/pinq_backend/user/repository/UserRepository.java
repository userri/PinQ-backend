package com.example.pinq_backend.user.repository;

import com.example.pinq_backend.user.domain.User;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByNickname(String nickname);

    Optional<User> findByOauthProviderAndOauthId(String oauthProvider, String oauthId);

    /** 알림이 켜져 있고 수신 시각이 주어진 30분 슬롯과 일치하는 사용자 (푸시 스케줄러용). */
    List<User> findAllByNotificationEnabledTrueAndNotificationTime(LocalTime slotTime);

    /**
     * streak 관련 필드를 직접 UPDATE — JPA dirty checking 우회.
     * clearAutomatically=true 로 1차 캐시를 비워 이후 조회 시 DB 최신값을 읽게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE User u SET u.currentStreak = :streak, u.maxStreak = :maxStreak, u.lastSolvedDate = :date WHERE u.id = :userId")
    void updateStreak(
        @Param("userId") Long userId,
        @Param("streak") int streak,
        @Param("maxStreak") int maxStreak,
        @Param("date") LocalDate date
    );
}
