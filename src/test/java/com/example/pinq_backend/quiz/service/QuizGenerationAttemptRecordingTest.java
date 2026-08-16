package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.audit.QuizGenerationAttemptRecorder;
import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 회차 구분이 시각 추측이 아니라 호출부 인자로 정해지는지 고정한다.
 *
 * 종전 집계 스크립트는 06:10 을 경계로 시각을 보고 정기/백필을 갈랐고,
 * 정기 회차가 늦어지면 그대로 오분류됐다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizGenerationAttemptRecordingTest {

    @Autowired
    private QuizGenerationAttemptRecorder recorder;

    @Autowired
    private QuizGenerationAttemptRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 회차_구분이_시각과_무관하게_기록된다() {
        repository.deleteAll();

        // 같은 시각에 두 회차를 남긴다 — 시각으로 갈랐다면 구분되지 않는다.
        recorder.record("STOCK", "REGULAR", "주식", "정기 기사", "https://example.com/a",
                AttemptStage.PUBLISHED, null, null, 1L);
        recorder.record("STOCK", "BACKFILL", "주식", "백필 기사", "https://example.com/b",
                AttemptStage.PUBLISHED, null, null, 2L);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        assertThat(rows).extracting(QuizGenerationAttempt::getRunWindow)
                .containsExactlyInAnyOrder("REGULAR", "BACKFILL");
    }

    @Test
    void 발행_행에는_quiz_id_가_박힌다() {
        repository.deleteAll();

        recorder.record("STOCK", "REGULAR", "주식", "발행된 기사", "https://example.com/c",
                AttemptStage.PUBLISHED, null, null, 463L);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        // 이 값이 없으면 검수 판정 등급을 나중에 결합할 수 없다 — 별건의 전제다.
        assertThat(rows).singleElement()
                .extracting(QuizGenerationAttempt::getQuizId)
                .isEqualTo(463L);
    }
}
