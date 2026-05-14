package com.example.pinq_backend.quiz.exception;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공통 예외 핸들러.
 *
 *  - QuizNotFoundException    → 404
 *  - InvalidChoiceException   → 400 (제출한 choiceId 가 해당 퀴즈의 보기가 아님)
 *  - IllegalStateException    → 503  (예: demo 유저 미존재 등 서버 상태 이상)
 *  - 요청 바디 검증 실패        → 400 + 어떤 필드가 문제인지
 *
 * Phase 1 최소 구현. 추후 ProblemDetail(RFC 7807) 또는 별도 ErrorResponse 클래스로 정돈 예정.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QuizNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleQuizNotFound(QuizNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "timestamp", OffsetDateTime.now().toString(),
            "status", 404,
            "error", "Not Found",
            "message", e.getMessage(),
            "quizId", e.getQuizId()
        ));
    }

    @ExceptionHandler(InvalidChoiceException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidChoice(InvalidChoiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", OffsetDateTime.now().toString(),
            "status", 400,
            "error", "Bad Request",
            "message", e.getMessage(),
            "quizId", e.getQuizId(),
            "choiceId", e.getChoiceId()
        ));
    }

    /**
     * 서버 초기화 상태가 깨진 경우 (예: demo 유저 미존재).
     *
     * 500 대신 503을 선택한 이유:
     *  - 500(Internal Server Error)은 "코드 버그"의 뉘앙스가 강하다.
     *  - 503(Service Unavailable)은 "서버가 현재 요청을 처리할 준비가 안 됐음"을 의미하며,
     *    demo 유저가 DB에 없는 상황(데이터 초기화 실패 등)을 더 정확하게 표현한다.
     *  - 클라이언트 입장에서 503은 "나중에 재시도 가능"이라는 신호이기도 하다.
     *
     * Phase 3(OAuth 도입) 시 이 핸들러는 제거하고 전용 예외 클래스로 교체 예정.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "timestamp", OffsetDateTime.now().toString(),
            "status", 503,
            "error", "Service Unavailable",
            "message", e.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<String> messages = e.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", OffsetDateTime.now().toString(),
            "status", 400,
            "error", "Bad Request",
            "messages", messages
        ));
    }
}
