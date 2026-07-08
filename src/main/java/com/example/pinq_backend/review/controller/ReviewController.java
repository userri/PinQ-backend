package com.example.pinq_backend.review.controller;

import com.example.pinq_backend.auth.SecurityUtils;
import com.example.pinq_backend.quiz.dto.AnswerRequest;
import com.example.pinq_backend.review.dto.ReviewAnswerResponse;
import com.example.pinq_backend.review.dto.TodayReviewsResponse;
import com.example.pinq_backend.review.service.ReviewService;
import com.example.pinq_backend.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 오답 복습("잔디에 물 주기") API.
 *
 *  GET  /api/reviews/today           : 오늘 복습 대상 목록 + 다음 예정일
 *  POST /api/reviews/{quizId}/answer : 복습 채점 (정답 → 주기 연장/졸업, 오답 → 리셋)
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final UserService userService;

    @GetMapping("/today")
    public TodayReviewsResponse getTodayReviews() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return reviewService.getTodayReviews(userId);
    }

    @PostMapping("/{quizId}/answer")
    public ReviewAnswerResponse answerReview(
        @PathVariable Long quizId,
        @Valid @RequestBody AnswerRequest request
    ) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return reviewService.answerReview(userId, quizId, request.choiceId());
    }
}
