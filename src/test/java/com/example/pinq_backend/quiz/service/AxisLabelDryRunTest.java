package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.news.client.NaverArticleScraper;
import com.example.pinq_backend.news.client.NaverNewsClient;
import com.example.pinq_backend.news.client.OpenAIQuizClient;
import com.example.pinq_backend.news.client.QuizSimilarityChecker;
import com.example.pinq_backend.quiz.dto.AxisLabelResponse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * axis 명명 수렴성 dry-run 로직 테스트 (스펙 1단계).
 *
 * 검증 대상: ① 앞 문항의 라벨이 뒤 호출의 knownAxes 로 들어가는가 (증분 주입)
 * ② 7일 창 안 동일 라벨이 wouldBlock 으로 집계되는가 ③ 라벨 실패 카운트.
 * 라벨 자체는 목으로 고정한다 — 실제 수렴성은 프로덕션 dry-run 이 판정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AxisLabelDryRunTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private NaverNewsClient naverNewsClient;
    @Mock private NaverArticleScraper naverArticleScraper;
    @Mock private OpenAIQuizClient openAIQuizClient;
    @Mock private QuizRepository quizRepository;
    @Mock private NewsArticleRepository newsArticleRepository;
    @Mock private TrialQuizRepository trialQuizRepository;

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
                new ObjectMapper()
        );

        // 8/1·8/3·8/12 세 문항 — 날짜 역순으로 반환해 정렬도 함께 검증한다
        when(quizRepository.findAllByQuizDateGreaterThanEqual(any())).thenReturn(List.of(
                QuizFixtures.sampleQuiz(3L, Category.EXCHANGE_RATE, "경쟁적 평가절하 질문",
                        LocalDate.of(2026, 8, 12), "경쟁적 평가절하: 정의"),
                QuizFixtures.sampleQuiz(1L, Category.EXCHANGE_RATE, "공동 외환개입 질문",
                        LocalDate.of(2026, 8, 1), "공동 외환시장 개입: 정의"),
                QuizFixtures.sampleQuiz(2L, Category.EXCHANGE_RATE, "엔 캐리트레이드 질문",
                        LocalDate.of(2026, 8, 3), "엔 캐리트레이드: 정의")
        ));
        when(openAIQuizClient.labelAxis(anyString(), anyString(), eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(Optional.of("엔화 약세"));
    }

    @Test
    void 앞_라벨이_뒤_호출의_knownAxes_로_들어간다() {
        service.labelAxes(Category.EXCHANGE_RATE, 30);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAIQuizClient).labelAxis(
                eq("경쟁적 평가절하 질문"), anyString(), eq(Category.EXCHANGE_RATE), captor.capture());
        assertThat(captor.getValue()).containsExactly("엔화 약세");
    }

    @Test
    void 칠일_내_동일_라벨은_wouldBlock() {
        AxisLabelResponse res = service.labelAxes(Category.EXCHANGE_RATE, 30);

        // 8/1 첫 등장 → false, 8/3 (2일 간격) → true, 8/12 (9일 간격) → false
        assertThat(res.items()).extracting(AxisLabelResponse.Item::quizId)
                .containsExactly(1L, 2L, 3L);
        assertThat(res.items()).extracting(AxisLabelResponse.Item::wouldBlock)
                .containsExactly(false, true, false);
        assertThat(res.wouldBlockCount()).isEqualTo(1);
        assertThat(res.labeled()).isEqualTo(3);
        assertThat(res.items().get(0).term()).isEqualTo("공동 외환시장 개입");
    }

    @Test
    void 라벨_실패는_실패_카운트로_센다() {
        when(openAIQuizClient.labelAxis(eq("엔 캐리트레이드 질문"), anyString(),
                eq(Category.EXCHANGE_RATE), anyList()))
                .thenReturn(Optional.empty());

        AxisLabelResponse res = service.labelAxes(Category.EXCHANGE_RATE, 30);

        assertThat(res.labelFailed()).isEqualTo(1);
        assertThat(res.labeled()).isEqualTo(2);
        assertThat(res.items()).extracting(AxisLabelResponse.Item::quizId)
                .containsExactly(1L, 3L);
    }
}
