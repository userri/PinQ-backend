package com.example.pinq_backend.quiz.controller;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.dto.TrialQuizResponse;
import com.example.pinq_backend.quiz.service.QuizGenerationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 퀴즈 수동 생성 어드민 API.
 *
 * POST /api/admin/quizzes/generate
 *  → 즉시 오늘 퀴즈 4개를 생성(또는 재생성)한다. ⚠️ 오늘 세트를 삭제 후 재생성 (파괴적).
 *  → 스케줄러 실행 전 테스트 / 장애 복구 시 사용.
 *
 * POST /api/admin/quizzes/test-generate?category=INTEREST_RATE
 *  → dry-run: 실제 파이프라인 전체를 통과시키되 저장하지 않고 결과를 반환한다.
 *  → 프롬프트·검증 변경 후 06시를 기다리지 않고 품질을 즉시 검수하는 용도.
 *  → category 필수 (전체 4개를 한 번에 돌리면 nginx 타임아웃(60s)을 넘길 수 있어
 *     카테고리 단위로 호출한다).
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

    @PostMapping("/test-generate")
    public ResponseEntity<TrialQuizResponse> testGenerate(
        @RequestParam("category") Category category
    ) {
        return ResponseEntity.ok(quizGenerationService.trialGenerate(category));
    }
}
