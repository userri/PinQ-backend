package com.example.pinq_backend.quiz.exception;

/**
 * 요청한 퀴즈 id 가 존재하지 않을 때 발생.
 * GlobalExceptionHandler 에서 HTTP 404 로 변환된다.
 */
public class QuizNotFoundException extends RuntimeException {

    private final Long quizId;

    public QuizNotFoundException(Long quizId) {
        super("Quiz not found: id=" + quizId);
        this.quizId = quizId;
    }

    public Long getQuizId() {
        return quizId;
    }
}
