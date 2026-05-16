package com.example.pinq_backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/users/register 요청 바디.
 */
public record RegisterRequest(
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 1, max = 30, message = "닉네임은 1~30자여야 합니다.")
    String nickname
) {}
