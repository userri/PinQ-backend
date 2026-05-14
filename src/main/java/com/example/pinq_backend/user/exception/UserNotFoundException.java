package com.example.pinq_backend.user.exception;

/**
 * 존재하지 않는 유저에 접근 시 발생 (HTTP 404).
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String nickname) {
        super("유저를 찾을 수 없습니다: " + nickname);
    }
}
