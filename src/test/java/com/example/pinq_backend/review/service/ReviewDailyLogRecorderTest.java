package com.example.pinq_backend.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.review.domain.ReviewDailyLog;
import com.example.pinq_backend.review.repository.ReviewDailyLogRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewDailyLogRecorderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final Long USER_ID = 10L;

    @Mock private ReviewDailyLogRepository reviewDailyLogRepository;
    @Mock private UserRepository userRepository;

    private ReviewDailyLogRecorder recorder;
    private final User user = User.builder().nickname("tester").build();

    @BeforeEach
    void setUp() {
        recorder = new ReviewDailyLogRecorder(reviewDailyLogRepository, userRepository);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(reviewDailyLogRepository.save(any(ReviewDailyLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("그날 첫 복습이면 로그를 새로 만든다 (정답 반영)")
    void firstReviewOfDay_createsLog() {
        when(reviewDailyLogRepository.findByUserIdAndReviewDate(USER_ID, TODAY))
                .thenReturn(Optional.empty());

        recorder.record(USER_ID, TODAY, true);

        ArgumentCaptor<ReviewDailyLog> captor = ArgumentCaptor.forClass(ReviewDailyLog.class);
        verify(reviewDailyLogRepository).save(captor.capture());
        assertThat(captor.getValue().getReviewDate()).isEqualTo(TODAY);
        assertThat(captor.getValue().getReviewedCount()).isEqualTo(1);
        assertThat(captor.getValue().getCorrectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 날 두 번째 복습이면 기존 로그를 증가시키고 새로 저장하지 않는다")
    void secondReviewOfDay_incrementsExisting() {
        ReviewDailyLog existing = ReviewDailyLog.firstReviewOfDay(user, TODAY, true);
        when(reviewDailyLogRepository.findByUserIdAndReviewDate(USER_ID, TODAY))
                .thenReturn(Optional.of(existing));

        recorder.record(USER_ID, TODAY, false); // 오답

        assertThat(existing.getReviewedCount()).isEqualTo(2);
        assertThat(existing.getCorrectCount()).isEqualTo(1); // 오답이라 정답 수 그대로
        verify(reviewDailyLogRepository, never()).save(any(ReviewDailyLog.class));
    }

    @Test
    @DisplayName("동시 생성 경합(유니크 제약 위반)은 예외를 삼키고 종료한다 — 채점에 전염 금지")
    void concurrentCreate_swallowed() {
        when(reviewDailyLogRepository.findByUserIdAndReviewDate(USER_ID, TODAY))
                .thenReturn(Optional.empty());
        when(reviewDailyLogRepository.save(any(ReviewDailyLog.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_daily_log"));

        recorder.record(USER_ID, TODAY, true); // 예외가 새어나오면 테스트 실패
    }
}
