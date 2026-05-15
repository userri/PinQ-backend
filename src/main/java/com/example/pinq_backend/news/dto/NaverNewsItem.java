package com.example.pinq_backend.news.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 네이버 뉴스 검색 API 응답의 개별 뉴스 항목.
 */
public record NaverNewsItem(
    String title,
    String originallink,
    String link,
    String description,
    String pubDate
) {
    /** HTML 태그 제거 후 제목 반환. */
    public String cleanTitle() {
        return title == null ? "" : title.replaceAll("<[^>]*>", "").trim();
    }

    /** HTML 태그 제거 후 내용 요약 반환. */
    public String cleanDescription() {
        return description == null ? "" : description.replaceAll("<[^>]*>", "").trim();
    }
}
