package com.example.pinq_backend.user.controller;

import com.example.pinq_backend.auth.SecurityUtils;
import com.example.pinq_backend.user.dto.AttemptSummaryResponse;
import com.example.pinq_backend.user.dto.BookmarkToggleResponse;
import com.example.pinq_backend.user.service.BookmarkService;
import com.example.pinq_backend.user.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 북마크 REST API.
 *
 *  GET    /api/bookmarks            : 내 북마크 목록 (최신순)
 *  POST   /api/bookmarks/{quizId}   : 북마크 추가 (idempotent)
 *  DELETE /api/bookmarks/{quizId}   : 북마크 해제 (idempotent)
 */
@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final UserService userService;

    @GetMapping
    public List<AttemptSummaryResponse> getMyBookmarks() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return bookmarkService.getBookmarks(userId);
    }

    @PostMapping("/{quizId}")
    public BookmarkToggleResponse addBookmark(@PathVariable Long quizId) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return bookmarkService.addBookmark(userId, quizId);
    }

    @DeleteMapping("/{quizId}")
    public BookmarkToggleResponse removeBookmark(@PathVariable Long quizId) {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return bookmarkService.removeBookmark(userId, quizId);
    }
}
