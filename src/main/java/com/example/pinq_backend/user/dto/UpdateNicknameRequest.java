package com.example.pinq_backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /api/users/me/nickname 요청 바디. */
public record UpdateNicknameRequest(
    @NotBlank(message = "닉네임은 비워둘 수 없어요")
    @Size(min = 1, max = 20, message = "닉네임은 1~20자 사이로 입력해주세요")
    String nickname
) {}
