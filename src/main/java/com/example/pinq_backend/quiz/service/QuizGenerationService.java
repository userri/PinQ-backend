package com.example.pinq_backend.quiz.service;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.news.client.NaverArticleScraper;
import com.example.pinq_backend.news.client.NaverNewsClient;
import com.example.pinq_backend.news.client.OpenAIQuizClient;
import com.example.pinq_backend.news.client.QuizSimilarityChecker;
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
import java.util.EnumMap;
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
            // INTEREST_RATE 키워드 보강(2026-07-11): "금리" 광범위 키워드가 시황·임명 기사를
            // 끌어와 무관 기사 유입 + 유사 중복 85.7%(172개 분석)의 원인이었음.
            // 구체적 키워드를 앞에 배치하고 "금리" 단독은 최후 폴백으로 유지.
            // "금리 인상" 같은 방향성 키워드는 금리 사이클이 바뀌면 빈 검색이 되므로 제외.
            Category.INTEREST_RATE, List.of("기준금리", "한국은행 금리", "예금 금리", "채권 금리", "대출 금리", "금리"),
            Category.EXCHANGE_RATE, List.of("원달러 환율", "달러 환율", "외환시장", "환율"),
            Category.STOCK,         List.of("코스피", "주가", "증시"),
            Category.REAL_ESTATE,   List.of("주택담보대출", "아파트 매매", "전세", "부동산"),
            // INFLATION(2026-07-11 신설): 생활 밀착 소재로 뉴스 풀이 매일 풍부.
            // "물가" 단독은 광범위해 최후 폴백으로.
            Category.INFLATION,     List.of("소비자물가", "물가 상승", "인플레이션", "물가")
    );

    /** 카테고리당 키워드별 후보 뉴스 개수 */
    private static final int NEWS_FETCH_COUNT = 10;

    /**
     * 생성·검증 프롬프트에 "이미 출제된 문제"로 주입할 이력 기간 (일).
     * 같은 카테고리의 최근 문항을 모델에게 보여줘 같은 개념의 재출제를 회피시킨다.
     * 카테고리당 하루 1문항이므로 30일 ≈ 문항 30개 ≈ 프롬프트 +1천 토큰 수준 (비용 무시 가능).
     */
    private static final int PROMPT_HISTORY_DAYS = 30;

    /**
     * 저장 전 렉시컬 유사도 검사 대상 이력 기간 (일).
     * 프롬프트 주입보다 길게 잡는 이유: 검사 비용이 0이라 기간을 늘려도 손해가 없고,
     * 운영 데이터에서 한 달 이상 간격을 두고 같은 문제가 재출제된 사례가 확인됐기 때문.
     */
    private static final int DEDUP_HISTORY_DAYS = 60;

    private static final DateTimeFormatter PUB_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NaverNewsClient naverNewsClient;
    private final NaverArticleScraper naverArticleScraper;
    private final OpenAIQuizClient openAIQuizClient;
    private final QuizRepository quizRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final Clock clock;
    private final QuizSimilarityChecker similarityChecker;
    private final com.example.pinq_backend.quiz.repository.TrialQuizRepository trialQuizRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper trialObjectMapper;

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

        // 중복 방지 이력 로드. 오늘 퀴즈 삭제 '이후'에 조회해야
        // 수동 재실행 시 방금 지운 오늘 퀴즈와 자기 자신이 충돌하지 않는다.
        DedupHistory history = loadDedupHistory(today);

        // 카테고리 간 중복 기사 방지: 이번 생성 사이클에서 사용된 URL을 추적
        Set<String> usedUrls = new HashSet<>();

        int generatedCount = 0;
        for (Category category : Category.values()) {
            try {
                boolean success = generateQuizForCategory(category, today, usedUrls, history);
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
     * @param history  최근 출제 이력 (프롬프트 주입용 + 저장 전 렉시컬 검사용)
     * @return 성공 여부
     */
    private boolean generateQuizForCategory(
            Category category,
            LocalDate today,
            Set<String> usedUrls,
            DedupHistory history
    ) {
        List<String> keywords = CATEGORY_KEYWORDS.getOrDefault(
                category, List.of(category.getDisplayName())
        );

        // 이 카테고리의 최근 문항 + 오늘 이미 생성된 문항(타 카테고리 포함).
        // 오늘 생성분을 넣는 이유: 같은 날 두 카테고리가 같은 개념을 내는 사례가
        // 운영 데이터에서 확인됐기 때문 (예: 기준금리→주담대 금리 경로가 하루 2회).
        List<String> promptHistory = history.promptQuestionsFor(category);

        for (String keyword : keywords) {
            List<NaverNewsItem> newsItems = naverNewsClient.search(keyword, NEWS_FETCH_COUNT);

            if (newsItems.isEmpty()) {
                log.warn("뉴스 검색 결과 없음. category={}, keyword={}", category, keyword);
                continue; // 다음 키워드로
            }

            for (NaverNewsItem item : newsItems) {
                String title = item.cleanTitle();
                if (title.isBlank()) continue;

                if (isEditorialTitle(title)) {
                    log.info("사설·칼럼 기사 건너뜀. category={}, title={}", category, title);
                    continue;
                }

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

                Optional<GeneratedQuizDto> quizOpt =
                        openAIQuizClient.generateQuiz(title, content, category, promptHistory);
                if (quizOpt.isEmpty()) {
                    log.info("기사 건너뜀 (SKIP 또는 생성 실패). category={}, title={}", category, title);
                    continue;
                }

                GeneratedQuizDto dto = quizOpt.get();
                if (!isValidQuiz(dto)) {
                    log.warn("OpenAI 응답 유효성 검증 실패. title={}", title);
                    continue;
                }

                // 저장 전 최종 방어선: 최근 이력과의 렉시컬 유사도 검사.
                // 프롬프트의 "중복 금지" 지시를 모델이 무시해도 여기서 걸러진다.
                Optional<QuizSimilarityChecker.Match> similar =
                        similarityChecker.findMostSimilar(dto.getQuestion(), history.lexicalPool());
                if (similar.isPresent()) {
                    QuizSimilarityChecker.Match match = similar.get();
                    log.info("최근 출제 이력과 유사하여 폐기. category={}, jaccard={}, dice={}, "
                                    + "candidate={}, existing={}",
                            category,
                            "%.2f".formatted(match.tokenJaccard()),
                            "%.2f".formatted(match.bigramDice()),
                            dto.getQuestion(), match.existingQuestion());
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

                // Quiz 저장. category 는 기사(article.category)가 아니라 '출제 슬롯'의
                // 카테고리를 저장한다 — 기사 재사용 시 라벨이 오염되는 문제를 원천 차단.
                quizRepository.save(
                        Quiz.builder()
                                .article(article)
                                .category(category)
                                .quizDate(today)
                                .question(dto.getQuestion())
                                .explanation(dto.getExplanation())
                                .keyword(dto.getKeyword())
                                .choices(choices)
                                .build()
                );

                // 사용된 URL 등록 (다른 카테고리에서 재사용 방지)
                usedUrls.add(url);

                // 생성된 문항을 이력에 등록 — 이번 사이클의 다음 카테고리부터
                // 프롬프트 주입·렉시컬 검사 양쪽에 반영된다.
                history.register(dto.getQuestion());

                log.info("퀴즈 생성 성공. category={}, keyword={}, title={}", category, keyword, title);
                return true;
            }

            log.info("키워드 후보 소진, 다음 키워드 시도. category={}, keyword={}", category, keyword);
        }

        log.warn("모든 키워드에서 퀴즈 생성 실패. category={}", category);
        return false;
    }

    /**
     * 퀴즈 생성 dry-run — 실제 파이프라인 전체(뉴스 검색 → 생성 → 룰베이스 →
     * Claude 검증 → 렉시컬 중복 검사)를 통과시키되 실데이터에는 아무것도 남기지 않는다.
     *
     * 용도: 프롬프트·검증 로직 변경 후 다음 날 06시를 기다리지 않고 즉시 품질 확인.
     * 이력에 등록하지 않으므로(no register) 반복 호출해도 실제 생성에 영향 없다.
     *
     * 룰 실험 워크벤치: extraGenRules/extraVerifyRules 로 룰 초안을 배포 없이 주입해
     * 효과를 확인할 수 있다. 결과는 실데이터와 분리된 trial_quiz 테이블에 자동 축적되어
     * 룰 버전 간 품질 비교(before/after)에 쓰인다.
     *
     * 주의: 후보 리젝 사유는 응답에 없고 서버 로그로 확인한다
     * (OpenAIQuizClient/QuizRuleValidator 가 이미 사유를 로깅함).
     */
    @Transactional
    public com.example.pinq_backend.quiz.dto.TrialQuizResponse trialGenerate(
            Category category, String extraGenRules, String extraVerifyRules, String model,
            String genPromptOverride) {
        LocalDate today = LocalDate.now(clock);
        DedupHistory history = loadDedupHistory(today);
        List<String> promptHistory = history.promptQuestionsFor(category);
        List<String> keywords = CATEGORY_KEYWORDS.getOrDefault(
                category, List.of(category.getDisplayName()));

        int tried = 0;
        for (String keyword : keywords) {
            for (NaverNewsItem item : naverNewsClient.search(keyword, NEWS_FETCH_COUNT)) {
                String title = item.cleanTitle();
                if (title.isBlank()) continue;
                if (isEditorialTitle(title)) {
                    log.info("[dry-run] 사설·칼럼 기사 건너뜀. title={}", title);
                    continue;
                }

                String content = naverArticleScraper.scrape(item.link())
                        .filter(s -> !s.isBlank())
                        .orElseGet(item::cleanDescription);
                if (content.isBlank()) continue;

                tried++;
                Optional<GeneratedQuizDto> quizOpt = openAIQuizClient.generateQuiz(
                        title, content, category, promptHistory, extraGenRules, extraVerifyRules,
                        model, genPromptOverride);
                if (quizOpt.isEmpty()) continue;

                GeneratedQuizDto dto = quizOpt.get();
                if (!isValidQuiz(dto)) continue;

                Optional<QuizSimilarityChecker.Match> similar =
                        similarityChecker.findMostSimilar(dto.getQuestion(), history.lexicalPool());
                if (similar.isPresent()) {
                    log.info("[dry-run] 이력 유사로 폐기. candidate={}, existing={}",
                            dto.getQuestion(), similar.get().existingQuestion());
                    continue;
                }

                String url = item.originallink() != null ? item.originallink() : item.link();
                saveTrial(category, true, tried, dto, title, url,
                        genRulesForLog(extraGenRules, genPromptOverride), extraVerifyRules, model);
                return com.example.pinq_backend.quiz.dto.TrialQuizResponse
                        .success(category.name(), tried, dto, title, url);
            }
        }
        saveTrial(category, false, tried, null, null, null,
                genRulesForLog(extraGenRules, genPromptOverride), extraVerifyRules, model);
        return com.example.pinq_backend.quiz.dto.TrialQuizResponse.failure(category.name(), tried);
    }

    /**
     * 사설·칼럼·기고류 판정 — 제목 코너명 기반 룰베이스 차단.
     *
     * 프롬프트 SKIP 기준("사설·칼럼 등 주관적 분석")이 있지만 LLM이 간헐적으로 놓침
     * (2026-07-12 실사고: "[기자의 눈]" 칼럼이 INFLATION 퀴즈로 발행됨).
     * 한국 언론의 오피니언 코너명은 제목에 정형화되어 있어 0원 룰베이스로 선차단한다.
     */
    private static final java.util.regex.Pattern EDITORIAL_TITLE = java.util.regex.Pattern.compile(
            "\\[\\s*(사설|칼럼|기고|오피니언|시론|논단|데스크|기자수첩|취재수첩|여적|만물상"
                    + "|기자의\\s*눈|데스크의\\s*눈|현장에서|편집국에서|특파원\\s*칼럼|[가-힣]{2,7}\\s*칼럼"
                    + "|[가-힣]{2,4}의\\s*(눈|창|시각|시선|수첩|칼럼|썰))\\s*\\]"
                    + "|^\\s*(사설|시론|기고)\\s*[:：\\-]");

    private static boolean isEditorialTitle(String title) {
        return EDITORIAL_TITLE.matcher(title).find();
    }

    /** 프롬프트 전면 교체 실험은 extra_gen_rules 컬럼에 마커와 함께 기록해 룰 주입 실험과 구분한다. */
    private static String genRulesForLog(String extraGenRules, String genPromptOverride) {
        if (genPromptOverride != null && !genPromptOverride.isBlank()) {
            return "[PROMPT_OVERRIDE]\n" + genPromptOverride;
        }
        return extraGenRules;
    }

    /** dry-run 결과를 실험 로그 테이블에 축적한다. 저장 실패가 dry-run 응답을 막지 않게 방어. */
    private void saveTrial(
            Category category, boolean success, int tried, GeneratedQuizDto dto,
            String articleTitle, String articleUrl, String extraGenRules, String extraVerifyRules,
            String model) {
        try {
            String choicesJson = dto == null ? null
                    : trialObjectMapper.writeValueAsString(dto.getChoices());
            trialQuizRepository.save(com.example.pinq_backend.quiz.domain.TrialQuiz.record(
                    category.name(), success, tried,
                    dto == null ? null : dto.getQuestion(),
                    choicesJson,
                    dto == null ? null : dto.getExplanation(),
                    dto == null ? null : dto.getKeyword(),
                    articleTitle, articleUrl, extraGenRules, extraVerifyRules, model));
        } catch (Exception e) {
            log.warn("[dry-run] 실험 로그 저장 실패 (응답에는 영향 없음)", e);
        }
    }

    /**
     * 이번 생성 사이클에서 쓰는 중복 방지 이력.
     *
     * @param promptQuestionsByCategory 카테고리별 최근 {@link #PROMPT_HISTORY_DAYS}일 문항.
     *                                  생성·검증 프롬프트에 "중복 금지" 목록으로 주입된다.
     * @param lexicalPool               최근 {@link #DEDUP_HISTORY_DAYS}일 전체 카테고리 문항.
     *                                  저장 전 렉시컬 유사도 검사 대상.
     * @param generatedToday            이번 사이클에서 새로 생성된 문항.
     *                                  프롬프트·렉시컬 검사 양쪽에 즉시 반영되어
     *                                  같은 날 카테고리 간 개념 중복을 막는다.
     */
    private record DedupHistory(
            Map<Category, List<String>> promptQuestionsByCategory,
            List<String> lexicalPool,
            List<String> generatedToday
    ) {
        /** 해당 카테고리의 프롬프트 주입용 문항 목록 (최근 이력 + 오늘 생성분). */
        List<String> promptQuestionsFor(Category category) {
            List<String> merged = new ArrayList<>(
                    promptQuestionsByCategory.getOrDefault(category, List.of()));
            merged.addAll(generatedToday);
            return merged;
        }

        /** 새로 생성된 문항을 이력에 등록. */
        void register(String question) {
            lexicalPool.add(question);
            generatedToday.add(question);
        }
    }

    /**
     * DB에서 최근 퀴즈를 읽어 중복 방지 이력을 구성한다.
     * 렉시컬 검사용 풀(60일·전체 카테고리)과 프롬프트 주입용(30일·카테고리별)을 한 쿼리로 만든다.
     *
     * 알려진 한계: 카테고리 분류는 article.category 를 프록시로 쓰는데, 저장 시
     * findByUrl 로 기존 기사를 재사용하면 기사의 '최초' 카테고리가 따라와 실제 출제
     * 카테고리와 어긋날 수 있다 (그 문항은 잘못된 카테고리의 프롬프트 이력에 들어감).
     * 렉시컬 풀은 카테고리 무관이라 영향이 없고, 근본 해결은 Quiz 에 category 컬럼을
     * 추가하는 것이다 (별도 마이그레이션 예정).
     */
    private DedupHistory loadDedupHistory(LocalDate today) {
        List<Quiz> recentQuizzes = quizRepository.findAllByQuizDateGreaterThanEqual(
                today.minusDays(DEDUP_HISTORY_DAYS));
        LocalDate promptFrom = today.minusDays(PROMPT_HISTORY_DAYS);

        Map<Category, List<String>> byCategory = new EnumMap<>(Category.class);
        List<String> lexicalPool = new ArrayList<>();
        for (Quiz quiz : recentQuizzes) {
            lexicalPool.add(quiz.getQuestion());
            if (!quiz.getQuizDate().isBefore(promptFrom)) {
                byCategory.computeIfAbsent(quiz.getCategory(), c -> new ArrayList<>())
                        .add(quiz.getQuestion());
            }
        }
        log.info("중복 방지 이력 로드. 렉시컬 풀={}건, 프롬프트 주입 대상={}건",
                lexicalPool.size(),
                byCategory.values().stream().mapToInt(List::size).sum());
        return new DedupHistory(byCategory, lexicalPool, new ArrayList<>());
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
