package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.audit.QuizGenerationAttemptRecorder;
import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.news.client.GenerationOutcome;
import com.example.pinq_backend.news.client.NaverArticleScraper;
import com.example.pinq_backend.news.client.NaverNewsClient;
import com.example.pinq_backend.news.client.OpenAIQuizClient;
import com.example.pinq_backend.news.client.QuizSimilarityChecker;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import com.example.pinq_backend.news.dto.NaverNewsItem;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.fixture.QuizFixtures;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.quiz.repository.TrialQuizRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

/**
 * 이력 기반 중복 방지 흐름 테스트.
 *
 * 렉시컬 유사도 검사는 mock 이 아닌 실제 {@link QuizSimilarityChecker} 를 사용해
 * "서비스 → 검사기" 통합 동작(임계값 포함)을 그대로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizGenerationServiceDedupTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 7);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private NaverNewsClient naverNewsClient;
    @Mock private NaverArticleScraper naverArticleScraper;
    @Mock private OpenAIQuizClient openAIQuizClient;
    @Mock private QuizRepository quizRepository;
    @Mock private NewsArticleRepository newsArticleRepository;
    @Mock private TrialQuizRepository trialQuizRepository;
    @Mock private QuizGenerationAttemptRecorder attemptRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private QuizGenerationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        service = new QuizGenerationService(
                naverNewsClient,
                naverArticleScraper,
                openAIQuizClient,
                quizRepository,
                newsArticleRepository,
                clock,
                new QuizSimilarityChecker(),
                trialQuizRepository,
                objectMapper,
                attemptRecorder
        );

        // 공통 기본 동작: 뉴스 없음 / 오늘 퀴즈 없음 / 이력 없음 / 저장은 인자 그대로 반환
        when(naverNewsClient.search(anyString(), anyInt())).thenReturn(List.of());
        when(quizRepository.findAllByQuizDate(TODAY)).thenReturn(List.of());
        when(quizRepository.findAllByQuizDateGreaterThanEqual(any())).thenReturn(List.of());
        when(quizRepository.save(any(Quiz.class))).thenAnswer(inv -> inv.getArgument(0));
        when(newsArticleRepository.findByUrl(anyString())).thenReturn(Optional.empty());
        when(newsArticleRepository.save(any(NewsArticle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(naverArticleScraper.scrape(anyString())).thenReturn(Optional.of("기사 본문"));
    }

    @Test
    @DisplayName("과거 이력과 유사한 문항은 폐기하고, 다음 기사에서 새 문항을 저장한다")
    void similarCandidate_isDiscarded_nextArticleIsUsed() throws Exception {
        // 3일 전 INTEREST_RATE 로 출제된 이력
        Quiz pastQuiz = QuizFixtures.sampleQuiz(
                1L, Category.INTEREST_RATE,
                "미국 국채 금리가 상승하면 일반적으로 주식 시장에 미치는 영향은 무엇인가요?",
                TODAY.minusDays(3)
        );
        when(quizRepository.findAllByQuizDateGreaterThanEqual(any()))
                .thenReturn(List.of(pastQuiz));

        // INTEREST_RATE 첫 키워드에서 기사 2건 검색됨
        when(naverNewsClient.search(eq("기준금리"), anyInt())).thenReturn(List.of(
                newsItem("기사A", "https://news.example.com/a"),
                newsItem("기사B", "https://news.example.com/b")
        ));

        // 기사A → 이력의 변주(중복), 기사B → 새로운 개념
        String duplicate = "미국 국채 금리가 상승할 경우 주식 시장에 미치는 영향은 무엇일까요?";
        String fresh = "콜금리와 기준금리의 가장 큰 차이는 무엇인가?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(duplicate)));
        when(openAIQuizClient.generateQuiz(eq("기사B"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(fresh)));

        int generated = service.generateTodayQuizzes();

        // 다른 3개 카테고리는 뉴스가 없어 실패, INTEREST_RATE 만 성공
        assertThat(generated).isEqualTo(1);

        // 중복 후보는 저장되지 않고 기사B의 새 문항만 저장된다
        ArgumentCaptor<Quiz> savedQuiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository, times(1)).save(savedQuiz.capture());
        assertThat(savedQuiz.getValue().getQuestion()).isEqualTo(fresh);

        // 생성 프롬프트에는 과거 이력 문항이 "중복 금지" 목록으로 전달된다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAIQuizClient, times(2)).generateQuiz(
                anyString(), anyString(), eq(Category.INTEREST_RATE), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(0))
                .contains(pastQuiz.getQuestion());
    }

    @Test
    @DisplayName("같은 사이클에서 방금 생성된 문항도 다음 카테고리의 중복 검사에 반영된다")
    void questionGeneratedInSameCycle_blocksLaterCategory() throws Exception {
        // INTEREST_RATE: 기사A 로 성공
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/a")));
        String first = "미국 국채 금리가 상승하면 일반적으로 주식 시장에 미치는 영향은 무엇인가요?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(first)));

        // EXCHANGE_RATE: 기사C 가 검색되지만 생성 결과가 방금 저장된 문항의 변주
        when(naverNewsClient.search(eq("원달러 환율"), anyInt()))
                .thenReturn(List.of(newsItem("기사C", "https://news.example.com/c")));
        String sameCycleDuplicate = "미국 국채 금리가 상승할 경우 주식 시장에 미치는 영향은 무엇일까요?";
        when(openAIQuizClient.generateQuiz(eq("기사C"), anyString(), eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(sameCycleDuplicate)));

        int generated = service.generateTodayQuizzes();

        // EXCHANGE_RATE 후보는 같은 사이클 생성분과의 중복으로 폐기되어 저장 1건만 발생
        assertThat(generated).isEqualTo(1);
        ArgumentCaptor<Quiz> savedQuiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository, times(1)).save(savedQuiz.capture());
        assertThat(savedQuiz.getValue().getQuestion()).isEqualTo(first);

        // EXCHANGE_RATE 생성 프롬프트에도 오늘 생성분이 [용어] 축과 함께 이력으로 전달된다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAIQuizClient).generateQuiz(
                eq("기사C"), anyString(), eq(Category.EXCHANGE_RATE), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).anyMatch(line -> line.endsWith(first));
    }

    // ── dry-run (trialGenerate) ──────────────────────────────────────────────

    @Test
    @DisplayName("dry-run 은 파이프라인을 통과한 퀴즈를 반환하되 실데이터에는 저장하지 않는다")
    void trialGenerate_returnsQuizWithoutSaving() throws Exception {
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/a")));
        String fresh = "콜금리와 기준금리의 가장 큰 차이는 무엇인가?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE),
                anyList(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(GenerationOutcome.success(quizDto(fresh)));

        var result = service.trialGenerate(Category.INTEREST_RATE, null, null, null, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.quiz().question()).isEqualTo(fresh);
        assertThat(result.candidatesTried()).isEqualTo(1);
        verify(quizRepository, never()).save(any());
        verify(newsArticleRepository, never()).save(any());
        // 실험 로그에는 축적된다
        verify(trialQuizRepository).save(any());
    }

    @Test
    @DisplayName("dry-run 도 이력 유사 후보를 폐기한다 (실제 파이프라인과 동일 기준)")
    void trialGenerate_rejectsSimilarCandidate() throws Exception {
        Quiz pastQuiz = QuizFixtures.sampleQuiz(
                1L, Category.INTEREST_RATE,
                "미국 국채 금리가 상승하면 일반적으로 주식 시장에 미치는 영향은 무엇인가요?",
                TODAY.minusDays(3));
        when(quizRepository.findAllByQuizDateGreaterThanEqual(any()))
                .thenReturn(List.of(pastQuiz));
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/a")));
        String duplicate = "미국 국채 금리가 상승할 경우 주식 시장에 미치는 영향은 무엇일까요?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE),
                anyList(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(GenerationOutcome.success(quizDto(duplicate)));

        var result = service.trialGenerate(Category.INTEREST_RATE, null, null, null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.candidatesTried()).isEqualTo(1);
        verify(quizRepository, never()).save(any());
    }

    private NaverNewsItem newsItem(String title, String url) {
        return new NaverNewsItem(title, url, url, "요약", "Tue, 07 Jul 2026 06:00:00 +0900");
    }

    @Test
    @DisplayName("7일 내 동일 keyword 용어(비본원)는 문장이 달라도 폐기하고 다음 기사를 쓴다")
    void recentSameTerm_isDiscarded() throws Exception {
        // 3일 전 같은 카테고리에서 "가계대출 총량 규제" 소재로 출제된 이력 (총량 규제 3연속 실측 재현)
        Quiz past = QuizFixtures.sampleQuiz(
                1L, Category.REAL_ESTATE,
                "가계대출 총량 규제가 강화되면 잔금대출 실수요자에게 어떤 영향이 있는가?",
                TODAY.minusDays(3),
                "가계대출 총량 규제: 금융당국이 은행별 대출 총량을 제한하는 정책");
        when(quizRepository.findAllByQuizDateGreaterThanEqual(any())).thenReturn(List.of(past));

        when(naverNewsClient.search(eq("부동산"), anyInt())).thenReturn(List.of(
                newsItem("기사A", "https://news.example.com/a"),
                newsItem("기사B", "https://news.example.com/b")
        ));

        // 기사A → 같은 용어·다른 문장 (렉시컬로는 못 잡는 케이스), 기사B → 새 소재
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.REAL_ESTATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(
                        "은행별 대출 한도를 정부가 관리하면 청약 당첨자의 자금 계획은 어떻게 되는가?",
                        "가계대출 총량 규제: 금융당국이 은행별 대출 총량을 제한하는 정책")));
        when(openAIQuizClient.generateQuiz(eq("기사B"), anyString(), eq(Category.REAL_ESTATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(
                        "전세가율이 높아지면 갭투자 수요는 왜 늘어나는가?",
                        "전세가율: 매매가 대비 전세가 비율")));

        int generated = service.generateTodayQuizzes();
        assertThat(generated).isEqualTo(1);

        // 같은 용어 후보는 버려지고 새 소재만 저장된다
        ArgumentCaptor<Quiz> savedQuiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository, times(1)).save(savedQuiz.capture());
        assertThat(savedQuiz.getValue().getKeyword()).startsWith("전세가율");

        // 프롬프트 이력 라인에는 소재 축 [용어] 가 앞에 붙는다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAIQuizClient, times(2)).generateQuiz(
                anyString(), anyString(), eq(Category.REAL_ESTATE), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(0))
                .anyMatch(line -> line.startsWith("[가계대출 총량 규제] "));
    }

    @Test
    @DisplayName("본원 용어(기준금리 등)는 7일 내 재사용해도 가드가 차단하지 않는다")
    void genericTerm_isExemptFromGuard() throws Exception {
        Quiz past = QuizFixtures.sampleQuiz(
                1L, Category.INTEREST_RATE,
                "기준금리가 인상되면 예금 금리는 왜 오르는가?",
                TODAY.minusDays(2),
                "기준금리: 중앙은행이 정하는 정책 금리");
        when(quizRepository.findAllByQuizDateGreaterThanEqual(any())).thenReturn(List.of(past));

        when(naverNewsClient.search(eq("기준금리"), anyInt())).thenReturn(List.of(
                newsItem("기사A", "https://news.example.com/a")
        ));
        // 같은 본원 용어지만 다른 개념의 문항 — 정상 통과해야 한다
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(
                        "기준금리와 콜금리의 차이는 무엇인가?",
                        "기준금리: 중앙은행이 정하는 정책 금리")));

        int generated = service.generateTodayQuizzes();
        assertThat(generated).isEqualTo(1);
        verify(quizRepository, times(1)).save(any(Quiz.class));
    }

    @Test
    @DisplayName("keyword 용어가 그 문항의 카테고리명과 같으면 폐기하고 다음 기사를 쓴다")
    void termEqualToCategoryName_isDiscarded() throws Exception {
        when(naverNewsClient.search(eq("원달러 환율"), anyInt())).thenReturn(List.of(
                newsItem("기사A", "https://news.example.com/a"),
                newsItem("기사B", "https://news.example.com/b")
        ));

        // 기사A → 용어가 카테고리명 그대로("환율") = 정보량 0. 실발행분 12건의 형태 그대로다.
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(
                        "달러 공급이 늘면 원화 가치는 어떻게 되는가?",
                        "환율: 두 통화 간 교환 비율")));
        when(openAIQuizClient.generateQuiz(eq("기사B"), anyString(), eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(
                        "외국인 자금이 유출되면 원화 가치는 왜 떨어지는가?",
                        "자본 유출: 국내 자산을 팔아 해외로 빠져나가는 자금 흐름")));

        int generated = service.generateTodayQuizzes();
        assertThat(generated).isEqualTo(1);

        ArgumentCaptor<Quiz> savedQuiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository, times(1)).save(savedQuiz.capture());
        assertThat(savedQuiz.getValue().getKeyword()).startsWith("자본 유출");
    }

    @Test
    @DisplayName("다른 카테고리 문항의 용어로 쓰인 카테고리명은 정보량이 있어 통과시킨다")
    void categoryNameAsTermOfAnotherCategory_isKept() throws Exception {
        when(naverNewsClient.search(eq("기준금리"), anyInt())).thenReturn(List.of(
                newsItem("기사A", "https://news.example.com/a")
        ));
        // 금리 문항의 용어가 "환율" — 겹치는 낱말이 없으므로 목록에서 중복으로 보이지 않는다
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(
                        "미국 금리가 오르면 원화 환율은 왜 상승 압력을 받는가?",
                        "환율: 두 통화 간 교환 비율")));

        int generated = service.generateTodayQuizzes();
        assertThat(generated).isEqualTo(1);
        verify(quizRepository, times(1)).save(any(Quiz.class));
    }

    // ── 사이클 내 실패 기사 메모 ─────────────────────────────────────────────

    @Test
    @DisplayName("한 키워드에서 실패한 기사는 같은 카테고리의 다음 키워드에서 다시 시도하지 않는다")
    void failedArticle_isNotRetriedAcrossKeywordsInSameCategory() throws Exception {
        // 같은 기사가 INFLATION 의 네 키워드 검색 결과에 함께 들어온다 (실운영의 키워드 겹침)
        NaverNewsItem shared = newsItem("겹치는기사", "https://news.example.com/shared");
        when(naverNewsClient.search(eq("소비자물가"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("물가 상승"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("인플레이션"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("물가"), anyInt())).thenReturn(List.of(shared));

        // 기사 속성 때문에 SKIP — 키워드가 바뀌어도 판정은 같다
        when(openAIQuizClient.generateQuiz(
                eq("겹치는기사"), anyString(), eq(Category.INFLATION), anyList()))
                .thenReturn(GenerationOutcome.failure(
                        com.example.pinq_backend.audit.domain.AttemptStage.GENERATE,
                        com.example.pinq_backend.audit.domain.AttemptReason.LLM_SKIP, null));

        int generated = service.generateTodayQuizzes();

        assertThat(generated).isZero();
        // 종전에는 키워드 수만큼 4회 호출됐다 — 스크래핑·생성 각 1회로 줄어든다
        verify(openAIQuizClient, times(1))
                .generateQuiz(eq("겹치는기사"), anyString(), eq(Category.INFLATION), anyList());
        verify(naverArticleScraper, times(1)).scrape(shared.link());
    }

    @Test
    @DisplayName("이미 다른 카테고리에서 쓰인 기사가 여러 키워드 검색에 겹쳐도 CROSS_CATEGORY_USED 는 한 번만 기록된다")
    void crossCategoryUsedArticle_isRecordedOnce_evenAcrossOverlappingKeywordSearches() throws Exception {
        // INTEREST_RATE: 기사A 로 성공 저장 — usedUrls 에 등록된다
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/shared")));
        String first = "미국 국채 금리가 상승하면 일반적으로 주식 시장에 미치는 영향은 무엇인가요?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(first)));

        // EXCHANGE_RATE: 키워드 4개 검색 결과 전부에 같은(이미 쓰인) 기사가 걸린다 — 실운영의
        // 키워드 겹침 재현. usedUrls 가드에서 매번 걸리므로 EXCHANGE_RATE 는 실패로 끝난다.
        NaverNewsItem shared = newsItem("기사A", "https://news.example.com/shared");
        when(naverNewsClient.search(eq("원달러 환율"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("달러 환율"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("외환시장"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("환율"), anyInt())).thenReturn(List.of(shared));

        int generated = service.generateTodayQuizzes();

        assertThat(generated).isEqualTo(1); // INTEREST_RATE 만 성공

        // CROSS_CATEGORY_USED 는 EXCHANGE_RATE 슬롯에서 정확히 한 번만 기록돼야 한다 —
        // triedUrls 가드보다 위에서 기록하면 키워드 수(4)만큼 부풀어 찍힌다.
        verify(attemptRecorder, times(1)).record(
                eq(Category.EXCHANGE_RATE.name()), anyString(), anyString(), anyString(),
                eq("https://news.example.com/shared"),
                eq(AttemptStage.PREFILTER), eq(AttemptReason.CROSS_CATEGORY_USED), isNull(), isNull());
    }

    @Test
    @DisplayName("발행 성공 시 PUBLISHED 로 기록되고 quizId 가 채워진다")
    void publishedAttempt_isRecordedWithNonNullQuizId() throws Exception {
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/a")));
        String fresh = "콜금리와 기준금리의 가장 큰 차이는 무엇인가?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(fresh)));
        when(quizRepository.save(any(Quiz.class))).thenAnswer(inv -> {
            Quiz quiz = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(quiz, "id", 42L);
            return quiz;
        });

        int generated = service.generateTodayQuizzes();

        assertThat(generated).isEqualTo(1);
        verify(attemptRecorder).record(
                eq(Category.INTEREST_RATE.name()), anyString(), eq("기준금리"), eq("기사A"),
                eq("https://news.example.com/a"),
                eq(AttemptStage.PUBLISHED), isNull(), isNull(), eq(42L));
    }

    @Test
    @DisplayName("생성 실패 시 클라이언트가 돌려준 stage/reason 이 그대로 기록된다")
    void generationFailure_recordsStageAndReasonFromOutcome() throws Exception {
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/a")));
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.failure(AttemptStage.GENERATE, AttemptReason.LLM_SKIP, "skip-detail"));

        int generated = service.generateTodayQuizzes();

        assertThat(generated).isZero();
        verify(attemptRecorder).record(
                eq(Category.INTEREST_RATE.name()), anyString(), eq("기준금리"), eq("기사A"),
                eq("https://news.example.com/a"),
                eq(AttemptStage.GENERATE), eq(AttemptReason.LLM_SKIP), eq("skip-detail"), isNull());
    }

    @Test
    @DisplayName("한 카테고리에서 실패한 기사라도 다른 카테고리 슬롯에서는 다시 판정한다")
    void failedArticle_isStillTriedByAnotherCategory() throws Exception {
        // 같은 기사가 두 카테고리 검색에 걸린다. 슬롯마다 정합 판정이 다르므로 재평가해야 한다
        NaverNewsItem shared = newsItem("겹치는기사", "https://news.example.com/shared");
        when(naverNewsClient.search(eq("기준금리"), anyInt())).thenReturn(List.of(shared));
        when(naverNewsClient.search(eq("원달러 환율"), anyInt())).thenReturn(List.of(shared));

        // INTEREST_RATE 슬롯에서는 부적합, EXCHANGE_RATE 슬롯에서는 통과
        when(openAIQuizClient.generateQuiz(
                eq("겹치는기사"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(GenerationOutcome.failure(
                        com.example.pinq_backend.audit.domain.AttemptStage.GENERATE,
                        com.example.pinq_backend.audit.domain.AttemptReason.LLM_SKIP, null));
        String fresh = "달러 공급이 늘면 원·달러 환율은 어느 방향으로 움직이는가?";
        when(openAIQuizClient.generateQuiz(
                eq("겹치는기사"), anyString(), eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(GenerationOutcome.success(quizDto(fresh)));

        int generated = service.generateTodayQuizzes();

        assertThat(generated).isEqualTo(1);
        ArgumentCaptor<Quiz> savedQuiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository, times(1)).save(savedQuiz.capture());
        assertThat(savedQuiz.getValue().getQuestion()).isEqualTo(fresh);
    }

    private GeneratedQuizDto quizDto(String question, String keyword) throws Exception {
        return objectMapper.readValue("""
                {
                  "skip": false,
                  "question": "%s",
                  "choices": [
                    {"orderNum": 1, "content": "보기1", "isAnswer": false},
                    {"orderNum": 2, "content": "보기2", "isAnswer": true},
                    {"orderNum": 3, "content": "보기3", "isAnswer": false},
                    {"orderNum": 4, "content": "보기4", "isAnswer": false}
                  ],
                  "explanation": "정답 해설입니다.",
                  "keyword": "%s"
                }
                """.formatted(question, keyword), GeneratedQuizDto.class);
    }

    private GeneratedQuizDto quizDto(String question) throws Exception {
        return objectMapper.readValue("""
                {
                  "skip": false,
                  "question": "%s",
                  "choices": [
                    {"orderNum": 1, "content": "보기1", "isAnswer": false},
                    {"orderNum": 2, "content": "보기2", "isAnswer": true},
                    {"orderNum": 3, "content": "보기3", "isAnswer": false},
                    {"orderNum": 4, "content": "보기4", "isAnswer": false}
                  ],
                  "explanation": "정답 해설입니다.",
                  "keyword": "핵심 용어: 한 줄 설명"
                }
                """.formatted(question), GeneratedQuizDto.class);
    }
}
