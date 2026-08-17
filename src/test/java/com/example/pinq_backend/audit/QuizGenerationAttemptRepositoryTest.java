package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 시도 행이 검수가 쓰는 롤업 모양(날짜×카테고리×stage×reason)으로 읽히는지 고정한다.
 *
 * 이 축이 깨지면 "어디서 걸렸나"와 "왜"의 분리가 무너져, 이 작업의 목적인
 * 기준 16 기여 분리가 불가능해진다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizGenerationAttemptRepositoryTest {

    @Autowired
    private QuizGenerationAttemptRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 날짜_카테고리_단계_사유로_롤업된다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);

        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "교보문고 베스트셀러", "https://example.com/1",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "이치방쿠지 체험기", "https://example.com/2",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "엔화 방어 국채 매각", "https://example.com/3",
                AttemptStage.PUBLISHED, null, null, 463L));

        List<QuizGenerationAttemptRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        assertThat(rows).hasSize(2);

        var failed = rows.stream()
                .filter(r -> r.getStage().equals("VERIFY"))
                .findFirst().orElseThrow();
        assertThat(failed.getReason()).isEqualTo("VERIFY_FAILED");
        assertThat(failed.getAttempts()).isEqualTo(2);

        var published = rows.stream()
                .filter(r -> r.getStage().equals("PUBLISHED"))
                .findFirst().orElseThrow();
        assertThat(published.getAttempts()).isEqualTo(1);
        // 발행 행은 탈락이 아니므로 reason 이 없다 — 분모를 이 행으로 센다.
        assertThat(published.getReason()).isNull();
    }

    /**
     * 밀린 날을 백필로 메우면 시도 수가 배로 뛴다 — 8/17 EXCHANGE_RATE 61건이 정기 22 /
     * 백필 39 였다. run 축이 없으면 그 스파이크가 기사 풀 악화인지 백필인지 구분되지 않고,
     * 손실률 분모가 회차 수만큼 부풀어 날짜 간 비교가 통째로 깨진다.
     */
    @Test
    void 정기와_백필이_같은_사유여도_따로_집계된다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);

        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "정기 회차 기사", "https://example.com/r",
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "BACKFILL",
                "환율", "백필 회차 기사 1", "https://example.com/b1",
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "BACKFILL",
                "환율", "백필 회차 기사 2", "https://example.com/b2",
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));

        List<QuizGenerationAttemptRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        // 다른 축(카테고리·stage·reason)이 전부 같아도 run 축으로 갈라져야 한다.
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(
                        QuizGenerationAttemptRepository.DailyRow::getRunWindow,
                        QuizGenerationAttemptRepository.DailyRow::getAttempts)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("REGULAR", 1L),
                        org.assertj.core.groups.Tuple.tuple("BACKFILL", 2L));
    }

    @Test
    void 하루치_원시_행을_시각순으로_돌려준다() {
        repository.deleteAll();
        LocalDate today = LocalDate.now(clock);
        LocalDateTime base = today.atTime(6, 0);

        repository.save(new QuizGenerationAttempt(base.plusMinutes(2), "STOCK", "REGULAR",
                "주식", "나중 기사", "https://example.com/b",
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null));
        repository.save(new QuizGenerationAttempt(base, "STOCK", "REGULAR",
                "주식", "먼저 기사", "https://example.com/a",
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null));

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(today);

        // 기사 제목을 눈으로 보는 경로다 — 순서가 흐트러지면 회차 흐름을 못 읽는다.
        assertThat(rows).extracting(QuizGenerationAttempt::getArticleTitle)
                .containsExactly("먼저 기사", "나중 기사");
    }
}
