package com.example.pinq_backend.quiz.exception;

/**
 * 제출한 choiceId 가 해당 퀴즈의 보기에 속하지 않을 때 발생.
 * GlobalExceptionHandler 에서 HTTP 400 으로 변환된다.
 */
public class InvalidChoiceException extends RuntimeException {

    private final Long quizId;
    private final Long choiceId;

    public InvalidChoiceException(Long quizId, Long choiceId) {
        super("Choice id=" + choiceId + " 는 Quiz id=" + quizId + " 의 보기가 아닙니다.");
        this.quizId = quizId;
        this.choiceId = choiceId;
    }

    public Long getQuizId() {
        return quizId;
    }

    public Long getChoiceId() {
        return choiceId;
    }
}
