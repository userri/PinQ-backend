package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.repository.QuizRepository;
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

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private NewsArticleRepository newsArticleRepository;

    /** quiz 는 article_id 가 NOT NULL 이라 기사를 먼저 심어야 저장된다. */
    private Quiz publishedQuiz() {
        NewsArticle article = newsArticleRepository.save(NewsArticle.builder()
                .category(Category.EXCHANGE_RATE)
                .title("환율 기사")
                .url("https://example.com/published")
                .source("테스트신문")
                .publishedAt(LocalDateTime.of(2026, 8, 17, 6, 0))
                .build());
        return Quiz.builder()
                .article(article)
                .category(Category.EXCHANGE_RATE)
                .quizDate(LocalDate.of(2026, 8, 17))
                .question("질문")
                .explanation("해설")
                .keyword("환율: 두 통화의 교환 비율")
                .choices(List.of(
                        Choice.builder().orderNum(1).content("가").answer(true).build(),
                        Choice.builder().orderNum(2).content("나").answer(false).build(),
                        Choice.builder().orderNum(3).content("다").answer(false).build(),
                        Choice.builder().orderNum(4).content("라").answer(false).build()))
                .build();
    }

    @Test
    void 날짜_카테고리_단계_사유로_롤업된다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);
        // 발행 행은 실재하는 퀴즈를 가리켜야 롤업에 남는다 — 사라진 퀴즈를 가리키는 행은
        // 재실행 잔재로 보고 걸러낸다(아래 사라진_퀴즈 테스트).
        Long quizId = quizRepository.save(publishedQuiz()).getId();

        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "교보문고 베스트셀러", "https://example.com/1",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "이치방쿠지 체험기", "https://example.com/2",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "엔화 방어 국채 매각", "https://example.com/3",
                AttemptStage.PUBLISHED, null, null, quizId));

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

    /**
     * 재실행한 날의 유령 분모를 막는다.
     *
     * {@code generateTodayQuizzes()} 는 그날 퀴즈를 지우고 다시 만드는데 계측 행은 별도
     * 트랜잭션으로 이미 커밋돼 있고 FK 도 없다(계측이 본 데이터 삭제를 막으면 안 되므로
     * 의도적이다). 그래서 재실행한 날은 {@code PUBLISHED} 행이 두 벌이 되고 오래된 쪽은
     * <b>존재하지 않는 quiz_id</b> 를 가리킨다. 이걸 그대로 세면 "그날만 발행이 두 배"인
     * 행이 나와 손실률 분모가 통째로 틀린다.
     */
    @Test
    void 사라진_퀴즈를_가리키는_발행행은_롤업에서_빠진다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);

        // 존재하지 않는 quiz_id — 재실행으로 원본 퀴즈가 지워진 상태를 흉내낸다.
        repository.save(new QuizGenerationAttempt(now, "STOCK", "REGULAR",
                "주식", "지워진 퀴즈의 기사", "https://example.com/gone",
                AttemptStage.PUBLISHED, null, null, 999_999_999L));
        // 탈락 행은 quiz_id 가 없다 — 필터에 걸려 사라지면 안 된다.
        repository.save(new QuizGenerationAttempt(now, "STOCK", "REGULAR",
                "주식", "탈락 기사", "https://example.com/drop",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));

        List<QuizGenerationAttemptRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        assertThat(rows).extracting(QuizGenerationAttemptRepository.DailyRow::getStage)
                .containsExactly("VERIFY");
    }

    /**
     * 재시도 배수(시도 ÷ 서로 다른 기사)의 분모를 고정한다.
     *
     * 종전 링버퍼 스크립트는 로그 줄의 {@code title=} 뒤 문자열을 키로 썼는데 그 뒤에
     * {@code stage=}·{@code reason=} 이 붙어 있어 <b>같은 기사가 다른 단계로 떨어지면
     * 다른 기사로 세어졌다</b>. 기사 수가 부풀면 배수는 낮게 나오고, "같은 풀을 다시
     * 훑는다"는 낭비가 실제보다 작아 보인다(8/17 고유 29건이 40 으로 잡혀 2.10× → 1.53×).
     * 그래서 제목이 아니라 URL 로 세고, 단계가 갈려도 한 건으로 묶여야 한다.
     */
    @Test
    void 같은_기사가_여러_단계로_떨어져도_기사_수는_하나다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);

        // 같은 URL 이 서로 다른 단계·사유로 두 번 — 옛 파서가 2건으로 세던 경우다.
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "환율 기사 (1차)", "https://example.com/same",
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "BACKFILL",
                "환율", "환율 기사 (재시도)", "https://example.com/same",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "다른 기사", "https://example.com/other",
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));

        List<QuizGenerationAttemptRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        // 시도는 3(같은 축끼리 묶여 2행), 서로 다른 기사는 2 → 배수 1.5×.
        assertThat(rows.stream().mapToLong(QuizGenerationAttemptRepository.DailyRow::getAttempts).sum())
                .isEqualTo(3);
        assertThat(rows).extracting(QuizGenerationAttemptRepository.DailyRow::getDistinctArticles)
                .containsOnly(2L);
    }

    /** 기사 없이 실패한 시도(URL null)는 분모에 끼지 않는다 — 배수가 실제보다 커진다. */
    @Test
    void 기사_URL_이_없는_행은_기사_수에_안_들어간다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);

        repository.save(new QuizGenerationAttempt(now, "STOCK", "REGULAR",
                "주식", "기사", "https://example.com/one",
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));
        repository.save(new QuizGenerationAttempt(now, "STOCK", "REGULAR",
                "주식", null, null,
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, null, null));

        List<QuizGenerationAttemptRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAttempts()).isEqualTo(2);
        assertThat(rows.get(0).getDistinctArticles()).isEqualTo(1);
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
