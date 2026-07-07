package com.example.pinq_backend.quiz.dto;

import com.example.pinq_backend.quiz.domain.Choice;
import com.example.pinq_backend.quiz.domain.Quiz;
import java.util.List;

/**
 * 퀴즈 풀이 화면용 응답 DTO.
 *
 * 의도적으로 노출하지 않는 것:
 *  - 정답 choice id (correctChoiceId) — 클라이언트 사이드 치팅 방지
 *  - explanation, keyword — 정답 채점 후에만 노출
 *  - article 의 published_at 등 상세 — 정답 화면에서만 의미 있음
 *
 * category 는 article 에서 파생한다.
 *
 * solved/correct 추가 배경:
 *  과거에는 서비스 단에서 풀이한 퀴즈를 리스트에서 제외했기 때문에
 *  "오늘 4문제 중 1개 풀고 돌아오면 3문제만 준비된 것처럼" 보이는 UX 문제가 있었다.
 *  이제는 풀이 여부를 응답에 함께 담아 프론트가 "1/4 완료" 또는 "남은 3개" 등
 *  컨텍스트 있는 표시를 할 수 있게 한다.
 */
public record QuizResponse(
    Long id,
    String category,
    String categoryDisplayName,
    String question,
    List<ChoiceResponse> choices,
    boolean solved,
    Boolean correct
) {

    public record ChoiceResponse(Long id, int orderNum, String content) {
        static ChoiceResponse from(Choice choice) {
            return new ChoiceResponse(
                choice.getId(),
                choice.getOrderNum(),
                choice.getContent()
            );
        }
    }

    /** 풀이 정보 없이 — 미풀이로 간주 (호환용). */
    public static QuizResponse from(Quiz quiz) {
        return from(quiz, false, null);
    }

    /**
     * 풀이 상태와 함께 변환.
     *
     * @param solved  사용자가 이 퀴즈를 한 번이라도 풀었는지
     * @param correct 첫 시도 정답 여부. solved=false면 null.
     */
    public static QuizResponse from(Quiz quiz, boolean solved, Boolean correct) {
        return new QuizResponse(
            quiz.getId(),
            quiz.getCategory().name(),
            quiz.getCategory().getDisplayName(),
            quiz.getQuestion(),
            quiz.getChoices().stream()
                .map(ChoiceResponse::from)
                .toList(),
            solved,
            correct
        );
    }
}
