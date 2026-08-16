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
