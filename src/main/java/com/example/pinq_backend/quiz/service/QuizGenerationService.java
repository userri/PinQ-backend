package com.example.pinq_backend.quiz.service;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.news.client.NaverArticleScraper;
import com.example.pinq_backend.news.client.NaverNewsClient;
import com.example.pinq_backend.news.client.OpenAIQuizClient;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import com.example.pinq_backend.news.dto.NaverNewsItem;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 뉴스 기반 퀴즈 자동 생성 서비스.
 *
 * 흐름:
 *  1. 카테고리별 검색 키워드로 네이버 뉴스 API 호출
 *  2. OpenAI API로 뉴스 → 4지선다 퀴즈 변환
 *  3. NewsArticle + Quiz + Choice를 DB에 저장
 *
 * 카테고리당 퀴즈 1개, 총 4개 생성 (INTEREST_RATE / EXCHANGE_RATE / STOCK / REAL_ESTATE).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizGenerationService {

    /** 카테고리 → 네이버 검색 키워드 매핑 */
    private static final Map<Category, String> CATEGORY_KEYWORDS = Map.of(
        Category.INTEREST_RATE, "금리",
        Category.EXCHANGE_RATE, "환율",
        Category.STOCK, "증시",
        Category.REAL_ESTATE, "부동산"
    );

    /** 카테고리당 후보 뉴스 개수 (퀴즈 생성 실패 시 다음 기사로 재시도) */
    private static final int NEWS_FETCH_COUNT = 5;

    private static final DateTimeFormatter PUB_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NaverNewsClient naverNewsClient;
    private final NaverArticleScraper naverArticleScraper;
    private final OpenAIQuizClient openAIQuizClient;
    private final QuizRepository quizRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final Clock clock;

    /**
     * 오늘의 퀴즈 4개를 생성·저장한다.
     * 이미 오늘 퀴즈가 있으면 삭제 후 재생성한다 (수동 재실행 지원).
     */
    @Transactional
    public int generateTodayQuizzes() {
        LocalDate today = LocalDate.now(clock);
        log.info("퀴즈 생성 시작. date={}", today);

        // 오늘 기존 퀴즈 삭제 (재실행 시 중복 방지)
        List<Quiz> existing = quizRepository.findAllByQuizDate(today);
        if (!existing.isEmpty()) {
            quizRepository.deleteAll(existing);
            log.info("기존 퀴즈 {} 개 삭제", existing.size());
        }

        int generatedCount = 0;
        for (Category category : Category.values()) {
            try {
                boolean success = generateQuizForCategory(category, today);
                if (success) generatedCount++;
            } catch (Exception e) {
                log.error("카테고리 {} 퀴즈 생성 중 예외 발생", category, e);
            }
        }

        log.info("퀴즈 생성 완료. 성공={}/{}", generatedCount, Category.values().length);
        return generatedCount;
    }

    /**
     * 특정 카테고리의 퀴즈 1개를 생성한다.
     *
     * @return 성공 여부
     */
    private boolean generateQuizForCategory(Category category, LocalDate today) {
        String keyword = CATEGORY_KEYWORDS.getOrDefault(category, category.getDisplayName());
        List<NaverNewsItem> newsItems = naverNewsClient.search(keyword, NEWS_FETCH_COUNT);

        if (newsItems.isEmpty()) {
            log.warn("뉴스 검색 결과 없음. category={}, keyword={}", category, keyword);
            return false;
        }

        for (NaverNewsItem item : newsItems) {
            String title = item.cleanTitle();
            if (title.isBlank()) continue;

            // 네이버 뉴스 본문 스크래핑 시도 → 실패 시 description(~150자 스니펫)으로 폴백
            String content = naverArticleScraper.scrape(item.link())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> {
                    log.info("스크래핑 폴백: description 사용. title={}", title);
                    return item.cleanDescription();
                });

            if (content.isBlank()) continue;

            Optional<GeneratedQuizDto> quizOpt = openAIQuizClient.generateQuiz(title, content);
            if (quizOpt.isEmpty()) {
                log.info("기사 건너뜀 (SKIP 또는 생성 실패). category={}, title={}", category, title);
                continue;
            }

            GeneratedQuizDto dto = quizOpt.get();
            if (!isValidQuiz(dto)) {
                log.warn("OpenAI 응답 유효성 검증 실패. title={}", title);
                continue;
            }

            // NewsArticle 저장 (URL 중복 시 기존 레코드 재사용)
            String url = item.originallink() != null ? item.originallink() : item.link();
            NewsArticle article = newsArticleRepository.findByUrl(url)
                .orElseGet(() -> newsArticleRepository.save(
                    NewsArticle.builder()
                        .title(title)
                        .url(url)
                        .source("네이버뉴스")
                        .publishedAt(parsePubDate(item.pubDate()))
                        .category(category)
                        .build()
                ));

            // Choice 리스트 생성
            List<Choice> choices = dto.getChoices().stream()
                .map(c -> Choice.builder()
                    .orderNum(c.getOrderNum())
                    .content(c.getContent())
                    .answer(c.isAnswer())
                    .build())
                .toList();

            // Quiz 저장
            quizRepository.save(
                Quiz.builder()
                    .article(article)
                    .quizDate(today)
                    .question(dto.getQuestion())
                    .explanation(dto.getExplanation())
                    .keyword(dto.getKeyword())
                    .choices(choices)
                    .build()
            );

            log.info("퀴즈 생성 성공. category={}, title={}", category, title);
            return true;
        }

        log.warn("모든 후보 뉴스에서 퀴즈 생성 실패. category={}", category);
        return false;
    }

    /** OpenAI 응답이 퀴즈 도메인 규칙을 충족하는지 검증. */
    private boolean isValidQuiz(GeneratedQuizDto dto) {
        if (dto.getQuestion() == null || dto.getQuestion().isBlank()) return false;
        if (dto.getChoices() == null || dto.getChoices().size() != 4) return false;
        if (dto.getExplanation() == null || dto.getExplanation().isBlank()) return false;

        long answerCount = dto.getChoices().stream()
            .filter(GeneratedQuizDto.ChoiceDto::isAnswer)
            .count();
        return answerCount == 1;
    }

    /**
     * 네이버 pubDate 파싱.
     * 형식: "Mon, 14 May 2026 10:00:00 +0900"
     * 파싱 실패 시 현재 시각 반환.
     */
    private java.time.LocalDateTime parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return java.time.LocalDateTime.now(clock);
        }
        try {
            return ZonedDateTime.parse(pubDate, PUB_DATE_FORMATTER).toLocalDateTime();
        } catch (Exception e) {
            log.warn("pubDate 파싱 실패: {}", pubDate);
            return java.time.LocalDateTime.now(clock);
        }
    }
}
