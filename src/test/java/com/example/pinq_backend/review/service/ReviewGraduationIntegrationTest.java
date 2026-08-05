package com.example.pinq_backend.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.article.repository.NewsArticleRepository;
import com.example.pinq_backend.config.AppConfig;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.repository.ReviewDailyLogRepository;
import com.example.pinq_backend.review.repository.ReviewItemRepository;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 복습 졸업 경로의 통합 테스트 — 실제 영속성 컨텍스트가 필요한 회귀만 담는다.
 *
 * 목(mock) 기반 {@link ReviewServiceTest} 로는 잡을 수 없는 종류의 버그를 다룬다.
 * 리포지토리를 목으로 두면 {@code @Modifying(clearAutomatically = true)} 가 아무 일도
 * 하지 않으므로, 컨텍스트가 비워져 lazy 프록시가 끊기는 사고가 통과해 버린다
 * (2026-08-05 실서버 500: 졸업 채점 시 LazyInitializationException).
 *
 * <b>클래스에 @Transactional 을 걸지 않는다.</b> 채점 경로의 일일 로그 기록이
 * REQUIRES_NEW 별도 트랜잭션이라, 테스트 트랜잭션이 감싸면 아직 커밋되지 않은 user 를
 * 그쪽에서 못 봐 FK 로 죽는다 — 실서버가 아니라 테스트 방식이 만드는 실패다.
 * 대신 뒷정리를 직접 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewGraduationIntegrationTest {

    @Autowired private ReviewService reviewService;
    @Autowired private UserRepository userRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private NewsArticleRepository newsArticleRepository;
    @Autowired private ReviewItemRepository reviewItemRepository;
    @Autowired private ReviewDailyLogRepository reviewDailyLogRepository;

    private Long userId;
    private Long quizId;
    private Long articleId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            reviewItemRepository.deleteAll(reviewItemRepository.findAllByUserId(userId));
            reviewDailyLogRepository
                .findByUserIdAndReviewDate(userId, LocalDate.now(AppConfig.KST))
                .ifPresent(reviewDailyLogRepository::delete);
            userRepository.deleteById(userId);
        }
        if (quizId != null) quizRepository.deleteById(quizId);
        if (articleId != null) newsArticleRepository.deleteById(articleId);
    }

    @Test
    @DisplayName("마지막 단계에서 정답이면 졸업 응답을 돌려준다 — 기사 링크가 끊기지 않는다")
    void answerReview_graduates_withoutLosingArticle() {
        LocalDate today = LocalDate.now(AppConfig.KST);
        User user = userRepository.saveAndFlush(User.builder().nickname("graduate-user").build());
        userId = user.getId();
        Quiz quiz = persistQuiz();
        quizId = quiz.getId();
        reviewItemRepository.saveAndFlush(lastStageItem(user, quizId, today));

        Long correctChoiceId = quiz.getAnswerChoice().getId();
        ReviewAnswerResponse response = reviewService.answerReview(userId, quizId, correctChoiceId);

        assertThat(response.correct()).isTrue();
        assertThat(response.graduated()).isTrue();
        assertThat(response.totalGraduatedTrees()).isEqualTo(1);
        // 졸업 연출 화면도 기사 링크를 띄운다 — 여기서 프록시가 끊기면 500 이 된다.
        assertThat(response.article()).isNotNull();
        assertThat(response.article().url()).isEqualTo("https://example.com/graduation-test");

        ReviewItem reloaded = reviewItemRepository.findByUserIdAndQuizId(userId, quizId).orElseThrow();
        assertThat(reloaded.isGraduated()).isTrue();
    }

    private Quiz persistQuiz() {
        NewsArticle article = newsArticleRepository.save(
            NewsArticle.builder()
                .category(Category.EXCHANGE_RATE)
                .title("졸업 테스트 기사")
                .url("https://example.com/graduation-test")
                .source("테스트신문")
                .publishedAt(LocalDateTime.of(2026, 8, 1, 9, 0))
                .build()
        );
        articleId = article.getId();
        return quizRepository.saveAndFlush(
            Quiz.builder()
                .article(article)
                .category(Category.EXCHANGE_RATE)
                .quizDate(LocalDate.of(2026, 8, 1))
                .question("졸업 테스트 문항")
                .explanation("졸업 테스트 해설")
                .keyword("테스트 용어 — 설명")
                .choices(List.of(
                    Choice.builder().orderNum(1).content("보기 1").answer(false).build(),
                    Choice.builder().orderNum(2).content("보기 2").answer(true).build(),
                    Choice.builder().orderNum(3).content("보기 3").answer(false).build(),
                    Choice.builder().orderNum(4).content("보기 4").answer(false).build()
                ))
                .build()
        );
    }

    /** 마지막 단계 · due 가 지난 항목 — 다음 정답이면 졸업하는 상태. */
    private ReviewItem lastStageItem(User user, Long quizId, LocalDate today) {
        ReviewItem item = ReviewItem.enqueue(user, quizId, today.minusDays(30));
        item.advanceOrGraduate(today.minusDays(27)); // stage 1
        item.advanceOrGraduate(today.minusDays(20)); // stage 2 (= MAX_STAGE)
        return item;
    }
}
