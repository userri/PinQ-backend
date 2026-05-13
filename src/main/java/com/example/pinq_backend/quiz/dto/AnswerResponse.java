package com.example.pinq_backend.quiz.dto;

import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.quiz.domain.Quiz;
import java.time.LocalDateTime;

/**
 * 정답 채점 결과 응답.
 *
 * Phase 1 대비 변경점:
 *  - selectedChoiceId / correctChoiceId 로 명명 통일 (DB 의 choice 와 맞춤)
 *  - keyword 필드 신규 (꼭 알아둘 단어)
 *  - relatedArticle → article 로 명명 변경, 필드 확장 (source, publishedAt)
 */
public record AnswerResponse(
    Long quizId,
    Long selectedChoiceId,
    boolean correct,
    Long correctChoiceId,
    String explanation,
    String keyword,
    ArticleResponse article
) {

    public record ArticleResponse(
        Long id,
        String title,
        String url,
        String source,
        String category,
        String categoryDisplayName,
        LocalDateTime publishedAt
    ) {
        static ArticleResponse from(NewsArticle a) {
            if (a == null) return null;
            return new ArticleResponse(
                a.getId(),
                a.getTitle(),
                a.getUrl(),
                a.getSource(),
                a.getCategory().name(),
                a.getCategory().getDisplayName(),
                a.getPublishedAt()
            );
        }
    }

    public static AnswerResponse of(Quiz quiz, Long selectedChoiceId) {
        return new AnswerResponse(
            quiz.getId(),
            selectedChoiceId,
            quiz.isCorrectAnswer(selectedChoiceId),
            quiz.getAnswerChoice().getId(),
            quiz.getExplanation(),
            quiz.getKeyword(),
            ArticleResponse.from(quiz.getArticle())
        );
    }
}
