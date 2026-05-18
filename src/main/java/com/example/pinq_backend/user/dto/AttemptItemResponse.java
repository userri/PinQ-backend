package com.example.pinq_backend.user.dto;

import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 오답노트 / 북마크 / 전체 풀이이력 화면이 공통으로 사용하는 응답 항목.
 *
 * 한 문제(quiz)에 대한:
 *  - 문제/선택지 정보
 *  - 사용자가 첫 시도에 고른 선택지(selectedChoiceId, legacy=null)
 *  - 정답/해설/keyword
 *  - 관련 기사
 *  - 북마크 여부
 *  - 첫 풀이 시각
 * 을 한 번에 담아 클라이언트가 화면을 즉시 그릴 수 있게 한다.
 */
public record AttemptItemResponse(
    Long quizId,
    String category,
    String categoryDisplayName,
    String question,
    List<ChoiceSummary> choices,
    Long selectedChoiceId,
    Long correctChoiceId,
    boolean correct,
    String explanation,
    String keyword,
    ArticleSummary article,
    boolean bookmarked,
    LocalDateTime solvedAt
) {

    public record ChoiceSummary(Long id, int orderNum, String content) {
        static ChoiceSummary from(Choice c) {
            return new ChoiceSummary(c.getId(), c.getOrderNum(), c.getContent());
        }
    }

    public record ArticleSummary(
        Long id,
        String title,
        String url,
        String source,
        String category,
        String categoryDisplayName,
        LocalDateTime publishedAt
    ) {
        static ArticleSummary from(NewsArticle a) {
            if (a == null) return null;
            return new ArticleSummary(
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

    /** Quiz + Attempt + 북마크여부 → 응답 DTO. */
    public static AttemptItemResponse of(
        Quiz quiz,
        UserQuizAttempt attempt,
        boolean bookmarked
    ) {
        NewsArticle article = quiz.getArticle();
        return new AttemptItemResponse(
            quiz.getId(),
            article.getCategory().name(),
            article.getCategory().getDisplayName(),
            quiz.getQuestion(),
            quiz.getChoices().stream().map(ChoiceSummary::from).toList(),
            attempt != null ? attempt.getFirstSelectedChoiceId() : null,
            quiz.getAnswerChoice().getId(),
            attempt != null && attempt.isFirstCorrect(),
            quiz.getExplanation(),
            quiz.getKeyword(),
            ArticleSummary.from(article),
            bookmarked,
            attempt != null ? attempt.getCreatedAt() : null
        );
    }
}
