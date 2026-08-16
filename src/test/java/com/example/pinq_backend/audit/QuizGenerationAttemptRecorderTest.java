package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 계측 저장이 본 기능(퀴즈 생성)을 깨지 않는지 고정한다.
 *
 * 이 테스트가 지키는 것은 데이터가 아니라 <b>가용성</b>이다 — 계측을 못 남기는 것보다
 * 퀴즈 생성이 멈추는 쪽이 훨씬 나쁘다. TokenUsageRecorder 와 같은 계약이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizGenerationAttemptRecorderTest {

    @Autowired
    private QuizGenerationAttemptRecorder recorder;

    @MockitoSpyBean
    private QuizGenerationAttemptRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 시도_한_건이_저장된다() {
        repository.deleteAll();

        recorder.record("INFLATION", "REGULAR", "물가", "8월 소비자물가 발표",
                "https://example.com/x", AttemptStage.VALIDATE,
                AttemptReason.TERM_REUSE_GUARD, "term=기준금리", null);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        assertThat(rows).hasSize(1);
        QuizGenerationAttempt row = rows.get(0);
        assertThat(row.getCategory()).isEqualTo("INFLATION");
        assertThat(row.getRunWindow()).isEqualTo("REGULAR");
        assertThat(row.getStage()).isEqualTo(AttemptStage.VALIDATE);
        assertThat(row.getReason()).isEqualTo(AttemptReason.TERM_REUSE_GUARD);
        assertThat(row.getDetail()).isEqualTo("term=기준금리");
    }

    @Test
    void 저장이_터져도_예외가_호출부로_새지_않는다() {
        doThrow(new RuntimeException("DB down"))
                .when(repository).save(any(QuizGenerationAttempt.class));

        // 여기서 예외가 새면 퀴즈 생성 루프가 통째로 죽는다.
        assertThatCode(() -> recorder.record("STOCK", "REGULAR", "주식", "제목",
                "https://example.com/y", AttemptStage.GENERATE,
                AttemptReason.API_ERROR, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void 상한을_넘는_문자열은_잘라서_저장한다() {
        repository.deleteAll();
        String longDetail = "가".repeat(400);

        // 자르지 않으면 MySQL 이 예외를 던지고, 그 예외는 삼켜져 계측만 조용히 사라진다.
        recorder.record("STOCK", "REGULAR", "주식", "제목", "https://example.com/z",
                AttemptStage.VALIDATE, AttemptReason.RULE_REJECTED, longDetail, null);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDetail()).hasSize(255);
    }
}
