package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.auth.SecurityUtils;
import com.example.pinq_backend.user.dto.AttemptItemResponse;
import com.example.pinq_backend.user.service.AttemptHistoryService;
import com.example.pinq_backend.user.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 풀이 이력 / 오답노트 REST API.
 *
 *  GET /api/me/attempts          : 내 전체 풀이 이력 (최신순)
 *  GET /api/me/attempts/{quizId} : 특정 문제 단건 상세 (미풀이도 마스킹해 반환)
 *  GET /api/me/wrong-notes       : 내 오답노트 (첫 시도 실패만, 최신순)
 *
 * 모든 응답은 AttemptItemResponse 로 통일 — 클라이언트는 동일한 카드 UI 로 렌더링.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class AttemptHistoryController {

    private final AttemptHistoryService attemptHistoryService;
    private final UserService userService;

    @GetMapping("/attempts")
    public List<AttemptItemResponse> getMyAttempts() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return attemptHistoryService.getAllAttempts(userId);
    }

    @GetMapping("/attempts/{quizId}")
    public AttemptItemResponse getMyAttemptDetail(@PathVariable Long quizId) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return attemptHistoryService.getAttemptDetail(userId, quizId);
    }

    @GetMapping("/wrong-notes")
    public List<AttemptItemResponse> getMyWrongNotes() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return attemptHistoryService.getWrongAttempts(userId);
    }
}
