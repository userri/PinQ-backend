package com.example.pinq_backend.quiz.controller;

import com.example.pinq_backend.quiz.dto.AnswerRequest;
import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse;
import com.example.pinq_backend.quiz.service.QuizService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 퀴즈 풀이/채점 REST API.
 *
 *  GET  /api/quizzes/today           : 오늘의 퀴즈 4개 반환 (정답/해설/keyword 미포함)
 *  POST /api/quizzes/{id}/answer     : 정답 채점 + 해설 + keyword + 관련 기사 반환
 */
@RestController
@RequestMapping("/api/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @GetMapping("/today")
    public List<QuizResponse> getTodayQuizzes() {
        return quizService.getTodayQuizzes();
    }

    @PostMapping("/{quizId}/answer")
    public AnswerResponse submitAnswer(
        @PathVariable Long quizId,
        @Valid @RequestBody AnswerRequest request
    ) {
        return quizService.checkAnswer(quizId, request.choiceId());
    }
}
