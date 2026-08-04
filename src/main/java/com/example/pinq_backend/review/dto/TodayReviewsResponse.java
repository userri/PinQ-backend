package com.example.pinq_backend.review.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 복습 세트.
 *
 * todayReviewed/todayCorrect 는 **하루 단위** 집계다. 하루 상한(5개)을 도입한 뒤로
 * 사용자의 단위는 세션이 아니라 하루이므로("오늘 물 줄 잔디 N개"), 완료 화면도
 * 세션 기준(4/4)이 아니라 오늘 기준(5개 중 4개)으로 말해야 한다.
 * 정원에서 1개 + 세션에서 4개를 풀면 오늘은 5개다 — 세션 카운트로는 4로 보인다.
 *
 * 원천은 {@code ReviewDailyLog} — "그날 몇 번 물 줬고 몇 번 맞혔나"라는 일어난 사실이라
 * 간격 규칙이 바뀌어도 안 깨진다. (ReviewItem 에 파생 컬럼을 두는 안은 기각:
 * "항목은 하루 한 번만 복습된다"는 규칙 가정에 기대는데, 이번 달에만 stage 리셋 폐기·
 * 하루 상한 도입으로 규칙이 두 번 바뀌었다.)
 *
 * @param reviews       오늘 복습 대상 (밀린 것 포함, due 오름차순)
 * @param nextDueDate   오늘 복습이 없거나 다 끝냈을 때 "다음 물 주기" 안내용.
 *                      예정된 복습이 아예 없으면 null.
 * @param todayReviewed 오늘 복습한 총 개수 (아직 안 했으면 0)
 * @param todayCorrect  그중 맞힌 개수 (아직 안 했으면 0)
 */
public record TodayReviewsResponse(
    List<ReviewQuizResponse> reviews,
    LocalDate nextDueDate,
    int todayReviewed,
    int todayCorrect
) {}
