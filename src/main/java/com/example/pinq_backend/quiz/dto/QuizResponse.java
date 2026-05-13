package com.example.pinq_backend.quiz.dto;

import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import java.util.List;

/**
 * 퀴즈 풀이 화면용 응답 DTO.
 *
 * 의도적으로 노출하지 않는 것:
 *  - 정답 choice id (correctChoiceId) — 클라이언트 사이드 치팅 방지
 *  - explanation, keyword — 정답 채점 후에만 노출
 *  - article 의 published_at 등 상세 — 정답 화면에서만 의미 있음
 *
 * category 는 article 에서 파생한다.
 */
public record QuizResponse(
    Long id,
    String category,
    String categoryDisplayName,
    String question,
    List<ChoiceResponse> choices
) {

    public record ChoiceResponse(Long id, int orderNum, String content) {
        static ChoiceResponse from(Choice choice) {
            return new ChoiceResponse(
                choice.getId(),
                choice.getOrderNum(),
                choice.getContent()
            );
        }
    }

    public static QuizResponse from(Quiz quiz) {
        NewsArticle article = quiz.getArticle();
        return new QuizResponse(
            quiz.getId(),
            article.getCategory().name(),
            article.getCategory().getDisplayName(),
            quiz.getQuestion(),
            quiz.getChoices().stream()
                .map(ChoiceResponse::from)
                .toList()
        );
    }
}
