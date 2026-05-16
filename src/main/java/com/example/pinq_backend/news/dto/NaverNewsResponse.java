package com.example.pinq_backend.news.dto;

import java.util.List;

/**
 * 네이버 뉴스 검색 API 전체 응답.
 */
public record NaverNewsResponse(
    String lastBuildDate,
    int total,
    int start,
    int display,
    List<NaverNewsItem> items
) {}
