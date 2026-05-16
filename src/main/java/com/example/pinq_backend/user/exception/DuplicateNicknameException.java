package com.example.pinq_backend.user.exception;

/**
 * 이미 존재하는 닉네임으로 회원가입 시도 시 발생 (HTTP 409).
 */
public class DuplicateNicknameException extends RuntimeException {

    public DuplicateNicknameException(String nickname) {
        super("이미 사용 중인 닉네임입니다: " + nickname);
    }
}
