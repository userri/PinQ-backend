package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.auth.SecurityUtils;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.RegisterRequest;
import com.example.pinq_backend.user.dto.RegisterResponse;
import com.example.pinq_backend.user.dto.UpdateNicknameRequest;
import com.example.pinq_backend.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 회원가입 / 탈퇴 / 닉네임 수정 API.
 *
 * POST   /api/users/register      — 닉네임 회원가입 (Phase 2 호환)
 * PATCH  /api/users/me/nickname   — 닉네임 수정 (JWT 필수)
 * DELETE /api/users/me            — 회원탈퇴
 *   - JWT 있음: JWT userId 로 탈퇴
 *   - JWT 없음: nickname 쿼리 파라미터로 탈퇴 (Phase 2 하위 호환)
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 닉네임 회원가입 (Phase 2 호환용).
     *
     * @param request { "nickname": "홍길동" }
     * @return 201 Created + { userId, nickname }
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
        @Valid @RequestBody RegisterRequest request
    ) {
        User user = userService.register(request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse.from(user));
    }

    /**
     * 닉네임 수정 (JWT 필수).
     *
     * @param request { "nickname": "새닉네임" }
     * @return 200 OK + { userId, nickname }
     */
    @PatchMapping("/me/nickname")
    public ResponseEntity<RegisterResponse> updateNickname(
        @Valid @RequestBody UpdateNicknameRequest request
    ) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        User user = userService.updateNickname(userId, request.nickname());
        return ResponseEntity.ok(RegisterResponse.from(user));
    }

    /**
     * 회원탈퇴 — JWT 필수 (SecurityConfig 에서 /api/users/me/** authenticated() 보호).
     *
     * @return 204 No Content — 탈퇴 성공
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        userService.withdraw(userId);
        return ResponseEntity.noContent().build();
    }
}
