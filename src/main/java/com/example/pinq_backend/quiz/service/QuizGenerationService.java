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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 뉴스 기반 퀴즈 자동 생성 서비스.
 *
 * 흐름:
 *  1. 카테고리별 검색 키워드 목록을 순서대로 시도해 네이버 뉴스 API 호출
 *  2. OpenAI API로 뉴스 → 4지선다 퀴즈 변환
 *  3. NewsArticle + Quiz + Choice를 DB에 저장
 *
 * 카테고리당 퀴즈 1개, 총 4개 생성 (INTEREST_RATE / EXCHANGE_RATE / STOCK / REAL_ESTATE).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizGenerationService {

    /**
     * 카테고리 → 네이버 검색 키워드 목록.
     * 앞 키워드에서 퀴즈 생성에 전부 실패하면 다음 키워드로 자동 폴백한다.
     * REAL_ESTATE는 "부동산" 단독 키워드가 무관 기사를 많이 섞어오므로
     * 더 구체적인 키워드들을 우선 배치했다.
     */
    private static final Map<Category, List<String>> CATEGORY_KEYWORDS = Map.of(
            Category.INTEREST_RATE, List.of("기준금리", "금리 인상", "금리"),
            Category.EXCHANGE_RATE, List.of("원달러 환율", "달러 환율", "환율"),
            Category.STOCK,         List.of("코스피", "주가", "증시"),
            Category.REAL_ESTATE,   List.of("주택담보대출", "아파트 매매", "전세", "부동산")
    );

    /** 카테고리당 키워드별 후보 뉴스 개수 */
    private static final int NEWS_FETCH_COUNT = 10;

    private static final DateTimeFormatter PUB_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NaverNewsClient naverNewsClient;
    private final NaverArticleScraper naverArticleScraper;
    private final OpenAIQuizClient openAIQuizClient;
    private final QuizRepository quizRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final Clock clock;

    /**
     * 오늘 퀴즈가 없을 때만 생성한다 — 자가치유 가드용. 있으면 아무것도 하지 않는다.
     * 자정 정기 생성이 실패했거나(외부 API 장애) 그 시각에 서버가 내려가 있던 경우
     * (배포/재시작 등)를 매시간 재시도로 복구한다.
     */
    @Transactional
    public int ensureTodayQuizzes() {
        LocalDate today = LocalDate.now(clock);
        if (!quizRepository.findAllByQuizDate(today).isEmpty()) {
            return 0;
        }
        log.info("오늘({}) 퀴즈가 없어 가드가 생성을 시작한다", today);
        return generateTodayQuizzes();
    }

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

        // 카테고리 간 중복 기사 방지: 이번 생성 사이클에서 사용된 URL을 추적
        Set<String> usedUrls = new HashSet<>();

        int generatedCount = 0;
        for (Category category : Category.values()) {
            try {
                boolean success = generateQuizForCategory(category, today, usedUrls);
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
     * 키워드 목록을 순서대로 시도하고, 한 키워드에서 모든 후보가 실패하면 다음 키워드로 넘어간다.
     *
     * @param usedUrls 이미 다른 카테고리에서 사용된 기사 URL 집합 (중복 기사 방지)
     * @return 성공 여부
     */
    private boolean generateQuizForCategory(
            Category category,
            LocalDate today,
            Set<String> usedUrls
    ) {
        List<String> keywords = CATEGORY_KEYWORDS.getOrDefault(
                category, List.of(category.getDisplayName())
        );

        for (String keyword : keywords) {
            List<NaverNewsItem> newsItems = naverNewsClient.search(keyword, NEWS_FETCH_COUNT);

            if (newsItems.isEmpty()) {
                log.warn("뉴스 검색 결과 없음. category={}, keyword={}", category, keyword);
                continue; // 다음 키워드로
            }

            for (NaverNewsItem item : newsItems) {
                String title = item.cleanTitle();
                if (title.isBlank()) continue;

                // 이미 다른 카테고리에서 사용한 기사 건너뜀
                String url = item.originallink() != null ? item.originallink() : item.link();
                if (usedUrls.contains(url)) {
                    log.info("중복 기사 건너뜀. category={}, url={}", category, url);
                    continue;
                }

                // 네이버 뉴스 본문 스크래핑 시도 → 실패 시 description(~150자 스니펫)으로 폴백
                String content = naverArticleScraper.scrape(item.link())
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> {
                            log.info("스크래핑 폴백: description 사용. title={}", title);
                            return item.cleanDescription();
                        });

                if (content.isBlank()) continue;

                Optional<GeneratedQuizDto> quizOpt = openAIQuizClient.generateQuiz(title, content, category);
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

                // Choice 리스트 생성: 생성 AI의 정답 위치 편향을 저장 전에 보정한다.
                List<Choice> choices = buildChoicesForDisplay(
                        dto,
                        new Random(choiceShuffleSeed(dto))
                );

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

                // 사용된 URL 등록 (다른 카테고리에서 재사용 방지)
                usedUrls.add(url);

                log.info("퀴즈 생성 성공. category={}, keyword={}, title={}", category, keyword, title);
                return true;
            }

            log.info("키워드 후보 소진, 다음 키워드 시도. category={}, keyword={}", category, keyword);
        }

        log.warn("모든 키워드에서 퀴즈 생성 실패. category={}", category);
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

    static List<Choice> buildChoicesForDisplay(GeneratedQuizDto dto, Random random) {
        List<GeneratedQuizDto.ChoiceDto> shuffled = new ArrayList<>(dto.getChoices());
        Collections.shuffle(shuffled, random);

        List<Choice> choices = new ArrayList<>(shuffled.size());
        for (int i = 0; i < shuffled.size(); i++) {
            GeneratedQuizDto.ChoiceDto c = shuffled.get(i);
            choices.add(Choice.builder()
                    .orderNum(i + 1)
                    .content(c.getContent())
                    .answer(c.isAnswer())
                    .build());
        }
        return choices;
    }

    static long choiceShuffleSeed(GeneratedQuizDto dto) {
        return Objects.hash(
                dto.getQuestion(),
                dto.getExplanation(),
                dto.getKeyword(),
                dto.getChoices().stream()
                        .map(GeneratedQuizDto.ChoiceDto::getContent)
                        .toList()
        );
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
