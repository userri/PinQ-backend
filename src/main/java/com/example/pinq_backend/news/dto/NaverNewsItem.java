package com.example.pinq_backend.news.dto;

import org.jsoup.parser.Parser;

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
    /**
     * HTML 태그 제거 + HTML 엔티티 디코딩 후 제목 반환.
     * 네이버 API는 title/description에 &quot; &amp; 등 HTML 엔티티를 포함해 응답하므로
     * 태그 제거 후 반드시 엔티티 디코딩이 필요하다.
     */
    public String cleanTitle() {
        if (title == null) return "";
        String stripped = title.replaceAll("<[^>]*>", "");
        return Parser.unescapeEntities(stripped, false).trim();
    }

    /** HTML 태그 제거 + HTML 엔티티 디코딩 후 내용 요약 반환. */
    public String cleanDescription() {
        if (description == null) return "";
        String stripped = description.replaceAll("<[^>]*>", "");
        return Parser.unescapeEntities(stripped, false).trim();
    }
}
