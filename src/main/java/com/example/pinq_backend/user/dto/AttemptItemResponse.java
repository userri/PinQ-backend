package com.example.pinq_backend.user.dto;

import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import java.time.LocalDate;
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
 *  - 복습 진행 상태(물 준 횟수/나무 여부)
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
    LocalDateTime solvedAt,
    ReviewStatus review
) {

    /**
     * 복습("물 주기") 진행 상태 — 복습 큐에 등록된 적 없는 문제면 null.
     *
     * @param graduated true 면 다 키운 나무 (더 이상 복습 안 나옴)
     */
    public record ReviewStatus(
        int stage,
        int waterCount,
        int absorbedCount,
        boolean graduated,
        LocalDate dueDate
    ) {
        static ReviewStatus from(ReviewItem item) {
            if (item == null) return null;
            return new ReviewStatus(
                item.getStage(),
                item.getWaterCount(),
                item.getAbsorbedCount(),
                item.isGraduated(),
                item.getDueDate()
            );
        }
    }

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

    /**
     * Quiz + Attempt + 북마크여부 → 응답 DTO.
     *
     * 미풀이(attempt == null) 항목은 정답·해설·keyword 를 마스킹한다:
     * 풀이 화면에서 문제를 미리 북마크할 수 있게 되면서, 북마크 목록이
     * 안 푼 문제의 정답을 노출하는 치팅 경로가 되는 것을 차단하기 위함.
     * (selectedChoiceId/solvedAt 이 null 인 것으로 클라이언트가 미풀이를 판별)
     */
    /** 기존 호출부(북마크 등) 호환 — 복습 상태 없이. */
    public static AttemptItemResponse of(
        Quiz quiz,
        UserQuizAttempt attempt,
        boolean bookmarked
    ) {
        return of(quiz, attempt, bookmarked, null);
    }

    public static AttemptItemResponse of(
        Quiz quiz,
        UserQuizAttempt attempt,
        boolean bookmarked,
        ReviewItem reviewItem
    ) {
        NewsArticle article = quiz.getArticle();
        boolean solved = attempt != null;
        return new AttemptItemResponse(
            quiz.getId(),
            quiz.getCategory().name(),          // 퀴즈 카테고리 (신뢰 원천)
            quiz.getCategory().getDisplayName(),
            quiz.getQuestion(),
            quiz.getChoices().stream().map(ChoiceSummary::from).toList(),
            solved ? attempt.getFirstSelectedChoiceId() : null,
            solved ? quiz.getAnswerChoice().getId() : null,
            solved && attempt.isFirstCorrect(),
            solved ? quiz.getExplanation() : null,
            solved ? quiz.getKeyword() : null,
            ArticleSummary.from(article),
            bookmarked,
            solved ? attempt.getCreatedAt() : null,
            ReviewStatus.from(reviewItem)
        );
    }
}
