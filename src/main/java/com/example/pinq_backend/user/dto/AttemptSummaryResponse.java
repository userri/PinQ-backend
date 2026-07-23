package com.example.pinq_backend.user.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import java.time.LocalDateTime;

/**
 * 오답노트 / 전체 이력 / 북마크 "목록" 응답 항목 — 접힌 카드가 쓰는 요약만.
 *
 * 목록 경량화(docs/api/wrong-notes-lightweight-request.md):
 * 접힌 카드는 질문·카테고리·상태 뱃지만 그리므로 무거운 필드
 * (choices/selectedChoiceId/correctChoiceId/explanation/keyword/article)를 싣지 않는다.
 * 펼친 카드의 상세는 GET /api/me/attempts/{quizId} (AttemptItemResponse) 가 담당.
 * 항목당 응답 크기 ~1/3, 오답 누적 시 선형 증가 완화.
 *
 * solved: 미풀이 북마크 판별용 명시 플래그 — 요약에는 correctChoiceId 가 없어
 * 기존 "correctChoiceId == null" 방식으로는 미풀이를 구분할 수 없다.
 */
public record AttemptSummaryResponse(
    Long quizId,
    String category,
    String categoryDisplayName,
    String question,
    boolean correct,
    boolean solved,
    boolean bookmarked,
    LocalDateTime solvedAt,
    AttemptItemResponse.ReviewStatus review
) {
    public static AttemptSummaryResponse of(
        Quiz quiz,
        UserQuizAttempt attempt,
        boolean bookmarked,
        ReviewItem reviewItem
    ) {
        boolean solved = attempt != null;
        return new AttemptSummaryResponse(
            quiz.getId(),
            quiz.getCategory().name(),
            quiz.getCategory().getDisplayName(),
            quiz.getQuestion(),
            solved && attempt.isFirstCorrect(),
            solved,
            bookmarked,
            solved ? attempt.getCreatedAt() : null,
            AttemptItemResponse.ReviewStatus.from(reviewItem)
        );
    }
}
