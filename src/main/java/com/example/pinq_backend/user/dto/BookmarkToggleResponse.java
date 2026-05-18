package com.example.pinq_backend.user.dto;

/**
 * 북마크 토글(추가/해제) 결과 응답.
 */
public record BookmarkToggleResponse(Long quizId, boolean bookmarked) {
}
