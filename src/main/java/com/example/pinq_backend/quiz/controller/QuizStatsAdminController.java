package com.example.pinq_backend.quiz.controller;

import com.example.pinq_backend.quiz.repository.QuizRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항별 난이도 통계 대시보드 (admin 전용 — AdminAuthFilter 가 X-Admin-Secret 검사).
 *
 * 일일 품질 검수(docs/quality-audit-log.md)와 함께 쓰는 난이도 측정 도구:
 *  - correctRate 가 목표 밴드(55~75%)를 벗어나는 문항 → 난이도 조정 실험 후보
 *  - avgElapsedSec 이 과도한 문항 → 질문 길이·복잡도 점검 후보
 *  - upvotes/downvotes 는 해설 화면의 선택적 피드백 (스파스 — 보조 신호)
 */
@RestController
@RequestMapping("/api/admin/quizzes")
@RequiredArgsConstructor
public class QuizStatsAdminController {

    private final QuizRepository quizRepository;
    private final Clock clock;

    @GetMapping("/stats")
    public List<QuizRepository.QuizStatRow> getQuizStats(
        @RequestParam(name = "days", defaultValue = "14") int days
    ) {
        LocalDate fromDate = LocalDate.now(clock).minusDays(Math.min(Math.max(days, 1), 365));
        return quizRepository.findQuizStatsSince(fromDate);
    }
}
