package com.example.pinq_backend.review.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 정원(복습 나무 현황) — "어떤 문제가 얼마나 자랐는가".
 *
 * growing 은 due 오름차순(급한 것 먼저), graduated 는 졸업 최신순.
 *
 * @param graduatedTrees 나무 총계 — users 카운터 값. 이 기능 배포 이전에 졸업한
 *                       나무는 row 가 없어 graduated 목록에는 없지만 총계에는 포함된다.
 *                       (목록 길이 ≠ 총계일 수 있음 — 프론트는 총계를 신뢰할 것)
 */
public record GardenResponse(
    List<GardenItem> growing,
    List<GardenItem> graduated,
    int graduatedTrees
) {
    /**
     * @param stage         현재 단계 (0~2). 졸업 항목은 마지막 단계 값 그대로
     * @param dueDate       다음 복습 예정일 (졸업 항목도 마지막 값이 남아 있으나 의미 없음)
     * @param waterCount    물 준 총 횟수
     * @param absorbedCount 흡수된(맞힌) 횟수
     * @param graduatedAt   졸업 시각. null 이면 자라는 중
     */
    public record GardenItem(
        Long quizId,
        String category,
        String categoryDisplayName,
        String question,
        String keyword,
        int stage,
        LocalDate dueDate,
        int waterCount,
        int absorbedCount,
        LocalDateTime graduatedAt
    ) {
        public static GardenItem of(ReviewItem item, Quiz quiz) {
            return new GardenItem(
                quiz.getId(),
                quiz.getCategory().name(),
                quiz.getCategory().getDisplayName(),
                quiz.getQuestion(),
                quiz.getKeyword(),
                item.getStage(),
                item.getDueDate(),
                item.getWaterCount(),
                item.getAbsorbedCount(),
                item.getGraduatedAt()
            );
        }
    }
}
