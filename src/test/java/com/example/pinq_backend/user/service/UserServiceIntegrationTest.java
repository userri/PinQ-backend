package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.config.AppConfig;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("답안 기록 시 users.current_streak / max_streak 컬럼까지 갱신한다")
    void recordAnswer_updatesUserStreakColumns() {
        LocalDate today = LocalDate.now(AppConfig.KST);
        User user = userRepository.saveAndFlush(
            User.builder()
                .nickname("streak-user")
                .currentStreak(0)
                .maxStreak(0)
                .build()
        );

        userService.recordAnswer(user.getId(), 100L, 1L, true);

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getCurrentStreak()).isEqualTo(1);
        assertThat(saved.getMaxStreak()).isEqualTo(1);
        assertThat(saved.getLastSolvedDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("기존 users streak 값이 꼬여 있어도 풀이 시도 기준으로 복구한다")
    void synchronizeStreak_repairsStaleUserColumns() {
        LocalDate today = LocalDate.now(AppConfig.KST);
        User user = userRepository.saveAndFlush(
            User.builder()
                .nickname("stale-streak-user")
                .currentStreak(0)
                .maxStreak(0)
                .build()
        );
        insertAttempt(user.getId(), 200L, today.minusDays(1).atTime(10, 0));
        insertAttempt(user.getId(), 201L, today.atTime(10, 0));

        userService.synchronizeStreak(user.getId());

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getCurrentStreak()).isEqualTo(2);
        assertThat(saved.getMaxStreak()).isEqualTo(2);
        assertThat(saved.getLastSolvedDate()).isEqualTo(today);
    }

    private void insertAttempt(Long userId, Long quizId, LocalDateTime createdAt) {
        entityManager.createNativeQuery("""
            INSERT INTO user_quiz_attempt
                (user_id, quiz_id, first_correct, first_selected_choice_id, created_at, updated_at)
            VALUES
                (:userId, :quizId, true, 1, :createdAt, :createdAt)
            """)
            .setParameter("userId", userId)
            .setParameter("quizId", quizId)
            .setParameter("createdAt", createdAt)
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }
}
