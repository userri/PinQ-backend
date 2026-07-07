package com.example.pinq_backend.quiz.fixture;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 테스트 픽스처 빌더.
 *
 * JPA 엔티티의 id 는 @GeneratedValue 라 어플리케이션 코드에선 세팅할 수 없으므로
 * 테스트에서 reflection 으로 직접 채워 넣는다.
 */
public final class QuizFixtures {

    private QuizFixtures() {}

    public static Quiz sampleQuiz(Long quizId, Category category, String question) {
        return sampleQuiz(quizId, category, question, /* correctOrderNum */ 2);
    }

    /** quizDate 가 있는 퀴즈 (생성 이력 조회 테스트용). */
    public static Quiz sampleQuiz(Long quizId, Category category, String question, LocalDate quizDate) {
        NewsArticle article = sampleArticle(1000L + quizId, category);
        List<Choice> choices = List.of(
            buildChoice(1L, 1, "보기 1", false),
            buildChoice(2L, 2, "보기 2", true),
            buildChoice(3L, 3, "보기 3", false),
            buildChoice(4L, 4, "보기 4", false)
        );
        Quiz quiz = Quiz.builder()
            .article(article)
            .category(category)
            .quizDate(quizDate)
            .question(question)
            .keyword("테스트 키워드 — 핵심 단어 설명")
            .explanation("테스트 해설")
            .choices(choices)
            .build();
        setId(quiz, quizId);
        return quiz;
    }

    /**
     * 옵션 4개 중 correctOrderNum 번째가 정답인 퀴즈를 만든다.
     * Choice id 는 1..4, Article id 는 1000+ 로 고정 (충돌 방지).
     */
    public static Quiz sampleQuiz(
        Long quizId,
        Category category,
        String question,
        int correctOrderNum
    ) {
        NewsArticle article = sampleArticle(1000L + quizId, category);
        List<Choice> choices = List.of(
            buildChoice(1L, 1, "보기 1", correctOrderNum == 1),
            buildChoice(2L, 2, "보기 2", correctOrderNum == 2),
            buildChoice(3L, 3, "보기 3", correctOrderNum == 3),
            buildChoice(4L, 4, "보기 4", correctOrderNum == 4)
        );
        Quiz quiz = Quiz.builder()
            .article(article)
            .category(category)
            .question(question)
            .keyword("테스트 키워드 — 핵심 단어 설명")
            .explanation("테스트 해설")
            .choices(choices)
            .build();
        setId(quiz, quizId);
        return quiz;
    }

    public static NewsArticle sampleArticle(Long id, Category category) {
        NewsArticle article = NewsArticle.builder()
            .category(category)
            .title("테스트 기사 제목 - " + category.name())
            .url("https://example.com/test/" + id)
            .source("테스트신문")
            .publishedAt(LocalDateTime.of(2026, 5, 12, 9, 0))
            .build();
        setId(article, id);
        return article;
    }

    private static Choice buildChoice(Long id, int orderNum, String content, boolean answer) {
        Choice choice = Choice.builder()
            .orderNum(orderNum)
            .content(content)
            .answer(answer)
            .build();
        setId(choice, id);
        return choice;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "테스트 픽스처에 id 를 세팅하지 못했습니다: " + entity.getClass(), e
            );
        }
    }
}
