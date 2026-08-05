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

    /**
     * 복습 졸업 카운터를 1 증가시킨다 — "나무 한 그루".
     *
     * read-modify-write 대신 DB 원자적 증가로 처리해, 여러 기기에서
     * 동시에 졸업 채점이 들어와도 카운트가 유실되지 않는다.
     *
     * <b>clearAutomatically 를 켜지 않는다.</b> 이 UPDATE 는 채점 트랜잭션 한가운데서
     * 불리는데, 1차 캐시를 비우면 호출자가 쥐고 있던 quiz·review_item 이 통째로 detach 돼
     * 뒤이어 응답 DTO 가 lazy 연관(기사)을 건드리는 순간 LazyInitializationException 이 난다
     * (2026-08-05 실서버: 졸업 채점만 500, 롤백돼 졸업 자체가 성립하지 않았다).
     * 갱신값은 아래 {@link #findGraduatedReviewCount} 가 스칼라 쿼리로 DB 에서 직접 읽으므로
     * 캐시를 비울 이유도 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE User u SET u.graduatedReviewCount = u.graduatedReviewCount + 1 WHERE u.id = :userId")
    void incrementGraduatedReviewCount(@Param("userId") Long userId);

    /** 졸업 나무 총계 — increment 직후에도 DB 최신값을 읽도록 스칼라 쿼리 사용. */
    @Query("SELECT u.graduatedReviewCount FROM User u WHERE u.id = :userId")
    int findGraduatedReviewCount(@Param("userId") Long userId);
}
