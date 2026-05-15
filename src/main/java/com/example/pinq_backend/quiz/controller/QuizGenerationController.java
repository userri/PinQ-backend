package com.example.pinq_backend.quiz.controller;

import com.example.pinq_backend.quiz.service.QuizGenerationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 퀴즈 수동 생성 어드민 API.
 *
 * POST /api/admin/quizzes/generate
 *  → 즉시 오늘 퀴즈 4개를 생성(또는 재생성)한다.
 *  → 스케줄러 실행 전 테스트 / 장애 복구 시 사용.
 */
@RestController
@RequestMapping("/api/admin/quizzes")
@RequiredArgsConstructor
public class QuizGenerationController {

    private final QuizGenerationService quizGenerationService;

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate() {
        int count = quizGenerationService.generateTodayQuizzes();
        return ResponseEntity.ok(Map.of(
            "message", "퀴즈 생성 완료",
            "generatedCount", count
        ));
    }
}
