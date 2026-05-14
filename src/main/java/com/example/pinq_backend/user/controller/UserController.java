package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.RegisterRequest;
import com.example.pinq_backend.user.dto.RegisterResponse;
import com.example.pinq_backend.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 회원가입 / 탈퇴 API.
 *
 * Phase 2: 인증 없이 nickname 으로 유저를 식별한다.
 * Phase 3: OAuth 토큰에서 userId 를 추출하는 방식으로 교체 예정.
 *
 * POST  /api/users/register  — 회원가입 (닉네임으로 유저 생성)
 * DELETE /api/users/me       — 회원탈퇴 (nickname 쿼리 파라미터로 식별, solved_history 포함 삭제)
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 회원가입.
     *
     * @param request { "nickname": "홍길동" }
     * @return 201 Created + { userId, nickname }
     *         409 Conflict  — 닉네임 중복
     *         400 Bad Request — 유효성 검증 실패
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
        @Valid @RequestBody RegisterRequest request
    ) {
        User user = userService.register(request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse.from(user));
    }

    /**
     * 회원탈퇴.
     *
     * Phase 2 에서는 인증이 없으므로 nickname 쿼리 파라미터로 유저를 특정한다.
     * 예: DELETE /api/users/me?nickname=홍길동
     *
     * @return 204 No Content — 탈퇴 성공
     *         404 Not Found  — 해당 닉네임 유저 없음
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
        @RequestParam String nickname
    ) {
        userService.withdraw(nickname);
        return ResponseEntity.noContent().build();
    }
}
