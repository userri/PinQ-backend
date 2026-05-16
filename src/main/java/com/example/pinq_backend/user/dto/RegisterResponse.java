package com.example.pinq_backend.user.dto;

import com.example.pinq_backend.user.domain.User;

/**
 * POST /api/users/register 응답.
 */
public record RegisterResponse(
    Long userId,
    String nickname
) {
    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getNickname());
    }
}
