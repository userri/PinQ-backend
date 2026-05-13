package com.example.pinq_backend.config;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 2 시드 데이터 로더.
 *
 * 순서:
 *  1) demo 유저 1명 (anonymous)
 *  2) 카테고리별 NewsArticle 4건
 *  3) 각 기사 기반 Quiz 4개 + 각 4개의 Choice
 *
 * 멱등: 이미 데이터가 있으면 건너뜀.
 */
@Component
@Profile({"local", "h2", "dev"})
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final QuizRepository quizRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedDemoUser();
        if (quizRepository.count() > 0) {
            log.info("[DataInitializer] 퀴즈 데이터가 이미 존재합니다. 시드 건너뜀.");
            return;
        }
        List<NewsArticle> articles = seedArticles();
        seedQuizzes(articles);
        log.info("[DataInitializer] User 1, Article {}, Quiz {} 시드 완료",
            articles.size(), articles.size());
    }

    private void seedDemoUser() {
        if (userRepository.findByNickname("demo").isPresent()) {
            return;
        }
        userRepository.save(User.builder()
            .nickname("demo")
            // oauth_* 는 Phase 3 까지 NULL
            .currentStreak(0)
            .maxStreak(0)
            .build());
    }

    private List<NewsArticle> seedArticles() {
        LocalDateTime base = LocalDateTime.now().withSecond(0).withNano(0);
        return newsArticleRepository.saveAll(List.of(
            NewsArticle.builder()
                .category(Category.INTEREST_RATE)
                .title("한은 금통위, 기준금리 동결 결정...연 3.25% 유지")
                .url("https://example.com/news/interest-rate-1")
                .source("더미경제신문")
                .publishedAt(base.minusHours(2))
                .build(),
            NewsArticle.builder()
                .category(Category.EXCHANGE_RATE)
                .title("원/달러 환율 1,300원대 진입...수출 기업 비상")
                .url("https://example.com/news/exchange-rate-1")
                .source("더미경제신문")
                .publishedAt(base.minusHours(4))
                .build(),
            NewsArticle.builder()
                .category(Category.STOCK)
                .title("코스피, 외국인 매수에 2% 급등...2,700선 회복")
                .url("https://example.com/news/stock-1")
                .source("더미경제신문")
                .publishedAt(base.minusHours(6))
                .build(),
            NewsArticle.builder()
                .category(Category.REAL_ESTATE)
                .title("정부, 수도권 LTV 규제 완화 검토...실수요자 숨통 트일까")
                .url("https://example.com/news/real-estate-1")
                .source("더미경제신문")
                .publishedAt(base.minusHours(8))
                .build()
        ));
    }

    private void seedQuizzes(List<NewsArticle> articles) {
        NewsArticle interest = articles.get(0);
        NewsArticle exchange = articles.get(1);
        NewsArticle stock    = articles.get(2);
        NewsArticle realEst  = articles.get(3);

        quizRepository.saveAll(List.of(
            Quiz.builder()
                .article(interest)
                .question("한국은행 기준금리를 결정하는 회의체의 이름은 무엇일까요?")
                .keyword("금융통화위원회 — 한국은행의 정책금리(기준금리)를 결정하는 최고 의사결정 기구.")
                .explanation("AI 해설이 여기 들어갑니다. (Phase 3 에서 실제 해설로 대체)")
                .choices(List.of(
                    Choice.builder().orderNum(1).content("금융통화위원회").answer(true).build(),
                    Choice.builder().orderNum(2).content("한국통화정책회의").answer(false).build(),
                    Choice.builder().orderNum(3).content("기획재정부 금리위원회").answer(false).build(),
                    Choice.builder().orderNum(4).content("한국금융감독원").answer(false).build()
                ))
                .build(),

            Quiz.builder()
                .article(exchange)
                .question("원/달러 환율이 1,400원에서 1,300원으로 떨어졌다면, 원화의 가치는 어떻게 변했을까요?")
                .keyword("원화 강세 — 같은 1달러를 사기 위해 더 적은 원을 내면 됨. 환율 하락 = 원화 가치 상승.")
                .explanation("AI 해설이 여기 들어갑니다. (Phase 3 에서 실제 해설로 대체)")
                .choices(List.of(
                    Choice.builder().orderNum(1).content("원화 가치가 하락했다").answer(false).build(),
                    Choice.builder().orderNum(2).content("원화 가치가 상승했다").answer(true).build(),
                    Choice.builder().orderNum(3).content("원화 가치는 변화 없다").answer(false).build(),
                    Choice.builder().orderNum(4).content("달러 가치도 함께 상승했다").answer(false).build()
                ))
                .build(),

            Quiz.builder()
                .article(stock)
                .question("코스피 지수가 전일 대비 2% 상승했다고 할 때, 이는 무엇을 의미할까요?")
                .keyword("KOSPI — 한국 상장기업 시가총액의 가중평균 지수. 모든 종목이 동일하게 오르는 게 아님.")
                .explanation("AI 해설이 여기 들어갑니다. (Phase 3 에서 실제 해설로 대체)")
                .choices(List.of(
                    Choice.builder().orderNum(1).content("코스피 상장 종목 모두가 2% 올랐다").answer(false).build(),
                    Choice.builder().orderNum(2).content("거래량이 2% 증가했다").answer(false).build(),
                    Choice.builder().orderNum(3).content("코스피 지수의 가중평균이 2% 올랐다").answer(true).build(),
                    Choice.builder().orderNum(4).content("외국인 매수세가 2% 늘었다").answer(false).build()
                ))
                .build(),

            Quiz.builder()
                .article(realEst)
                .question("주택담보대출의 LTV(Loan To Value)는 무엇을 의미할까요?")
                .keyword("LTV — 주택 가격(담보 가치) 대비 대출 한도 비율. 60% 면 5억 집에 3억까지 대출 가능.")
                .explanation("AI 해설이 여기 들어갑니다. (Phase 3 에서 실제 해설로 대체)")
                .choices(List.of(
                    Choice.builder().orderNum(1).content("주택 가격 대비 대출 한도 비율").answer(true).build(),
                    Choice.builder().orderNum(2).content("연소득 대비 대출 한도 비율").answer(false).build(),
                    Choice.builder().orderNum(3).content("보증금 대비 월세 비율").answer(false).build(),
                    Choice.builder().orderNum(4).content("주택 면적 대비 대출 금액").answer(false).build()
                ))
                .build()
        ));
    }
}
