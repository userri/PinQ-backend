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
                objectMapper
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
                .thenReturn(Optional.of(quizDto(duplicate)));
        when(openAIQuizClient.generateQuiz(eq("기사B"), anyString(), eq(Category.INTEREST_RATE), anyList()))
                .thenReturn(Optional.of(quizDto(fresh)));

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
                .thenReturn(Optional.of(quizDto(first)));

        // EXCHANGE_RATE: 기사C 가 검색되지만 생성 결과가 방금 저장된 문항의 변주
        when(naverNewsClient.search(eq("원달러 환율"), anyInt()))
                .thenReturn(List.of(newsItem("기사C", "https://news.example.com/c")));
        String sameCycleDuplicate = "미국 국채 금리가 상승할 경우 주식 시장에 미치는 영향은 무엇일까요?";
        when(openAIQuizClient.generateQuiz(eq("기사C"), anyString(), eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(Optional.of(quizDto(sameCycleDuplicate)));

        int generated = service.generateTodayQuizzes();

        // EXCHANGE_RATE 후보는 같은 사이클 생성분과의 중복으로 폐기되어 저장 1건만 발생
        assertThat(generated).isEqualTo(1);
        ArgumentCaptor<Quiz> savedQuiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository, times(1)).save(savedQuiz.capture());
        assertThat(savedQuiz.getValue().getQuestion()).isEqualTo(first);

        // EXCHANGE_RATE 생성 프롬프트에도 오늘 생성분이 이력으로 전달된다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAIQuizClient).generateQuiz(
                eq("기사C"), anyString(), eq(Category.EXCHANGE_RATE), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).contains(first);
    }

    // ── dry-run (trialGenerate) ──────────────────────────────────────────────

    @Test
    @DisplayName("dry-run 은 파이프라인을 통과한 퀴즈를 반환하되 실데이터에는 저장하지 않는다")
    void trialGenerate_returnsQuizWithoutSaving() throws Exception {
        when(naverNewsClient.search(eq("기준금리"), anyInt()))
                .thenReturn(List.of(newsItem("기사A", "https://news.example.com/a")));
        String fresh = "콜금리와 기준금리의 가장 큰 차이는 무엇인가?";
        when(openAIQuizClient.generateQuiz(eq("기사A"), anyString(), eq(Category.INTEREST_RATE),
                anyList(), isNull(), isNull()))
                .thenReturn(Optional.of(quizDto(fresh)));

        var result = service.trialGenerate(Category.INTEREST_RATE, null, null);

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
                anyList(), isNull(), isNull()))
                .thenReturn(Optional.of(quizDto(duplicate)));

        var result = service.trialGenerate(Category.INTEREST_RATE, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.candidatesTried()).isEqualTo(1);
        verify(quizRepository, never()).save(any());
    }

    private NaverNewsItem newsItem(String title, String url) {
        return new NaverNewsItem(title, url, url, "요약", "Tue, 07 Jul 2026 06:00:00 +0900");
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
