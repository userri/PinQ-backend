package com.example.pinq_backend.quiz.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuizCategoryTest {

    @Test
    @DisplayName("category 가 저장돼 있으면 기사 카테고리와 달라도 저장된 값을 반환한다")
    void getCategory_prefersStoredValue() {
        // 기사는 INTEREST_RATE 인데 출제 슬롯은 REAL_ESTATE 인 재사용 상황
        NewsArticle article = article(Category.INTEREST_RATE);
        Quiz quiz = quiz(article, Category.REAL_ESTATE);

        assertThat(quiz.getCategory()).isEqualTo(Category.REAL_ESTATE);
    }

    @Test
    @DisplayName("category 가 null 인 구 레코드는 기사 카테고리로 폴백한다")
    void getCategory_fallsBackToArticle() {
        NewsArticle article = article(Category.STOCK);
        Quiz quiz = quiz(article, null);

        assertThat(quiz.getCategory()).isEqualTo(Category.STOCK);
    }

    private NewsArticle article(Category category) {
        return NewsArticle.builder()
                .category(category)
                .title("제목")
                .url("https://example.com/" + category.name())
                .source("출처")
                .publishedAt(LocalDateTime.of(2026, 7, 7, 9, 0))
                .build();
    }

    private Quiz quiz(NewsArticle article, Category category) {
        return Quiz.builder()
                .article(article)
                .category(category)
                .quizDate(java.time.LocalDate.of(2026, 7, 7))
                .question("문제")
                .explanation("해설")
                .keyword("용어: 설명")
                .choices(List.of(
                        Choice.builder().orderNum(1).content("보기1").answer(false).build(),
                        Choice.builder().orderNum(2).content("보기2").answer(true).build(),
                        Choice.builder().orderNum(3).content("보기3").answer(false).build(),
                        Choice.builder().orderNum(4).content("보기4").answer(false).build()
                ))
                .build();
    }
}
