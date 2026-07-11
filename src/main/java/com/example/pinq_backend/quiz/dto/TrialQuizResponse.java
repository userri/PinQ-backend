package com.example.pinq_backend.quiz.dto;

import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import java.util.List;

/**
 * 퀴즈 생성 dry-run 결과 (admin 전용 — 검수용이라 정답을 노출한다).
 *
 * 실제 생성과 동일한 파이프라인(뉴스 검색 → 생성 → 룰베이스 → Claude 검증 →
 * 렉시컬 중복 검사)을 통과한 결과지만 DB 에는 저장하지 않는다.
 * 오늘 세트·이력·잔디에 아무 영향 없음.
 *
 * @param category        시도한 카테고리
 * @param success         파이프라인을 통과한 퀴즈가 나왔는지
 * @param candidatesTried 시도한 후보 기사 수 (리젝된 것 포함)
 * @param quiz            통과한 퀴즈 (success=false 면 null)
 */
public record TrialQuizResponse(
    String category,
    boolean success,
    int candidatesTried,
    TrialQuiz quiz
) {
    public record TrialQuiz(
        String question,
        List<TrialChoice> choices,
        String explanation,
        String keyword,
        String articleTitle,
        String articleUrl
    ) {}

    public record TrialChoice(int orderNum, String content, boolean answer) {}

    public static TrialQuizResponse success(
        String category, int tried, GeneratedQuizDto dto, String articleTitle, String articleUrl
    ) {
        return new TrialQuizResponse(category, true, tried, new TrialQuiz(
            dto.getQuestion(),
            dto.getChoices().stream()
                .map(c -> new TrialChoice(c.getOrderNum(), c.getContent(), c.isAnswer()))
                .toList(),
            dto.getExplanation(),
            dto.getKeyword(),
            articleTitle,
            articleUrl
        ));
    }

    public static TrialQuizResponse failure(String category, int tried) {
        return new TrialQuizResponse(category, false, tried, null);
    }
}
