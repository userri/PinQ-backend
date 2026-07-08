package com.example.pinq_backend.review.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 복습 세트.
 *
 * @param reviews     오늘 복습 대상 (밀린 것 포함, due 오름차순)
 * @param nextDueDate 오늘 복습이 없거나 다 끝냈을 때 "다음 물 주기" 안내용.
 *                    예정된 복습이 아예 없으면 null.
 */
public record TodayReviewsResponse(
    List<ReviewQuizResponse> reviews,
    LocalDate nextDueDate
) {}
