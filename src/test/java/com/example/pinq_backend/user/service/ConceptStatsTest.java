package com.example.pinq_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.user.dto.ConceptStatsResponse;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 취약 개념 진단 검증 — 정답률 계산, 표본 부족 제외, 동률 처리.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConceptStatsTest {

    private static final Long USER_ID = 10L;

    @Mock private UserService userService;
    @Mock private UserQuizAttemptRepository userQuizAttemptRepository;

    private UserStatsService service;

    @BeforeEach
    void setUp() {
        service = new UserStatsService(
                userService, userQuizAttemptRepository,
                Clock.system(ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("카테고리별 정답률을 계산하고, 표본 3개 이상 중 최저 정답률을 weakest 로 고른다")
    void conceptStats_weakestByRate() {
        when(userQuizAttemptRepository.countByCategory(USER_ID)).thenReturn(List.<Object[]>of(
                new Object[]{"INTEREST_RATE", 10L, 8L},  // 80%
                new Object[]{"EXCHANGE_RATE", 12L, 6L},  // 50% ← weakest
                new Object[]{"STOCK", 8L, 6L},           // 75%
                new Object[]{"REAL_ESTATE", 2L, 0L}      // 0% 지만 표본 2개 — 제외
        ));

        ConceptStatsResponse response = service.getConceptStats(USER_ID);

        assertThat(response.categories()).hasSize(4);
        assertThat(response.weakest()).isNotNull();
        assertThat(response.weakest().category()).isEqualTo("EXCHANGE_RATE");
        assertThat(response.weakest().displayName()).isEqualTo("환율");
        assertThat(response.weakest().correctRate()).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("정답률 동률이면 표본이 많은 쪽을 weakest 로 고른다 (더 신뢰할 수 있는 진단)")
    void conceptStats_tieBrokenBySampleSize() {
        when(userQuizAttemptRepository.countByCategory(USER_ID)).thenReturn(List.<Object[]>of(
                new Object[]{"INTEREST_RATE", 4L, 2L},   // 50%, 표본 4
                new Object[]{"STOCK", 10L, 5L}           // 50%, 표본 10 ← weakest
        ));

        ConceptStatsResponse response = service.getConceptStats(USER_ID);

        assertThat(response.weakest().category()).isEqualTo("STOCK");
    }

    @Test
    @DisplayName("모든 카테고리가 표본 미달이면 weakest 는 null (섣부른 진단 방지)")
    void conceptStats_allBelowThreshold_noWeakest() {
        when(userQuizAttemptRepository.countByCategory(USER_ID)).thenReturn(List.<Object[]>of(
                new Object[]{"INTEREST_RATE", 2L, 0L},
                new Object[]{"STOCK", 1L, 1L}
        ));

        ConceptStatsResponse response = service.getConceptStats(USER_ID);

        assertThat(response.categories()).hasSize(2);
        assertThat(response.weakest()).isNull();
    }

    @Test
    @DisplayName("풀이 이력이 없으면 빈 목록 + weakest null")
    void conceptStats_empty() {
        when(userQuizAttemptRepository.countByCategory(USER_ID)).thenReturn(List.of());

        ConceptStatsResponse response = service.getConceptStats(USER_ID);

        assertThat(response.categories()).isEmpty();
        assertThat(response.weakest()).isNull();
    }
}
