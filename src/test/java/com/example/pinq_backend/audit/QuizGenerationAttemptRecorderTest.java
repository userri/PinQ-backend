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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 스파이로 예외를 삼키는 걸 확인하는 것과, REQUIRES_NEW 가 바깥 트랜잭션을 실제로
     * 지켜주는 것은 다른 주장이다 — 후자를 real DB NOT NULL 위반으로 직접 증명한다.
     *
     * {@code stage} 는 nullable=false 라 null 로 넘기면 Hibernate 가 flush 시점에
     * (IDENTITY 전략이라 즉시) PropertyValueException 을 던진다 — mock 이 아니라
     * 진짜 영속성 계층 실패다. REQUIRES_NEW 가 없다면 이 실패가 바깥 트랜잭션의
     * EntityManager/세션까지 오염시켜, 바깥에서 먼저 저장한 정상 행마저 커밋되지
     * 못하고 사라진다.
     */
    @Test
    void 바깥_트랜잭션은_REQUIRES_NEW_덕에_실제_제약_위반에도_살아남는다() {
        repository.deleteAll();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.execute(status -> {
            // 바깥 트랜잭션에서 먼저 정상 행 1건을 저장한다 — 이게 살아남아야 격리 증명이다.
            repository.save(new QuizGenerationAttempt(
                    LocalDateTime.now(clock), "STOCK", "REGULAR",
                    null, null, null, AttemptStage.GENERATE, null, null, null));

            // stage=null → NOT NULL 위반. 실제 Hibernate/H2 실패지 mock 이 아니다.
            recorder.record("STOCK", "REGULAR", null, null, null,
                    null, AttemptReason.API_ERROR, "boom", null);
            return null;
        });

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        // 바깥에서 저장한 정상 행만 남는다 — 실패한 계측 저장은 흔적을 남기지 않는다.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(AttemptStage.GENERATE);
    }

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
