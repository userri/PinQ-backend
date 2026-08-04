package com.example.pinq_backend.user.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.review.domain.ReviewItem;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import java.time.LocalDateTime;

/**
 * 오답노트 / 전체 이력 / 북마크 "목록" 응답 항목 — 목록 행이 쓰는 요약만.
 *
 * 목록 경량화(docs/api/wrong-notes-lightweight-request.md):
 * 목록 행에 필요 없는 무거운 필드(choices/selectedChoiceId/correctChoiceId/
 * explanation/article)는 싣지 않는다.
 *
 * keyword 는 예외로 되살렸다(2026-08-04): 목록 행의 제목으로 쓰기 위해서다.
 * 생성 프롬프트에 문제 자립성 규칙이 들어간 뒤 question 이 길어져 2줄로도 잘리는데,
 * 잘린 복문이 행마다 쌓이면 목록을 훑을 수 없다. 짧은 명사(keyword)를 제목으로 두면
 * 스캔이 된다. 길이가 question 의 1/10 수준이라 경량화 취지와도 충돌하지 않는다.
 * 상세 화면의 데이터는 GET /api/me/attempts/{quizId} (AttemptItemResponse) 가 담당.
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
    /** 핵심 경제 용어. "용어: 한 줄 설명" 형식이며 목록은 콜론 앞만 제목으로 쓴다.
     *  미풀이 항목은 정답 힌트가 되므로 null (AttemptItemResponse 와 같은 마스킹 정책). */
    String keyword,
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
            // 미풀이 북마크에 keyword 를 주면 안 푼 문제의 개념이 새어 치팅 경로가 된다
            solved ? quiz.getKeyword() : null,
            solved && attempt.isFirstCorrect(),
            solved,
            bookmarked,
            solved ? attempt.getCreatedAt() : null,
            AttemptItemResponse.ReviewStatus.from(reviewItem)
        );
    }
}
