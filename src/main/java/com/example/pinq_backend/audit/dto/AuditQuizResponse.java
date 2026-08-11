package com.example.pinq_backend.audit.dto;

import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import java.time.LocalDate;
import java.util.List;

/**
 * 검수용 퀴즈 전문 — 사용자 응답 DTO 와 달리 **정답(answer)을 포함한다.**
 *
 * ⚠️ 이 표현이 사용자에게 새면 그날 문제의 치트시트가 된다. 노출 경로는
 * {@code /api/admin/**} 하나뿐이고 AdminAuthFilter 의 X-Admin-Secret 이 유일한 문이다.
 * 정답을 빼면 치명 4종 중 3종(복수 정답·정답 방향 오류·전제-정답 모순)을 판정할 수 없어
 * "치명 0" 이 빈 확인이 되므로, 가리는 선택지는 없다 — 가드로만 막는다.
 */
public record AuditQuizResponse(
        Long id,
        String category,
        LocalDate quizDate,
        String question,
        String explanation,
        String keyword,
        List<AuditChoiceResponse> choices
) {

    public record AuditChoiceResponse(int orderNum, String content, boolean answer) {
        static AuditChoiceResponse from(Choice choice) {
            return new AuditChoiceResponse(
                    choice.getOrderNum(), choice.getContent(), choice.isAnswer());
        }
    }

    public static AuditQuizResponse from(Quiz quiz) {
        return new AuditQuizResponse(
                quiz.getId(),
                quiz.getCategory() != null ? quiz.getCategory().name() : null,
                quiz.getQuizDate(),
                quiz.getQuestion(),
                quiz.getExplanation(),
                quiz.getKeyword(),
                quiz.getChoices().stream().map(AuditChoiceResponse::from).toList());
    }
}
