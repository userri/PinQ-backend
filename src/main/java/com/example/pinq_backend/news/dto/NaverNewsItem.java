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
     * HTML 엔티티 디코딩 후 HTML 태그 제거하여 제목 반환.
     *
     * 네이버 API는 title/description 에 &lt;b&gt;...&lt;/b&gt; 처럼
     * 태그 자체가 엔티티 인코딩된 형태로 올 수 있다.
     * 태그를 먼저 제거하면 이 인코딩된 태그는 통과되고,
     * 이후 디코딩 단계에서 실제 태그로 변환되어 마크업이 잔류한다.
     * 따라서 디코딩을 먼저 수행하여 모든 태그를 실제 태그로 변환한 뒤 제거한다.
     */
    public String cleanTitle() {
        if (title == null) return "";
        String decoded = Parser.unescapeEntities(title, false);
        return decoded.replaceAll("<[^>]*>", "").trim();
    }

    /**
     * HTML 엔티티 디코딩 후 HTML 태그 제거하여 내용 요약 반환.
     *
     * cleanTitle() 과 동일한 이유로 디코딩을 먼저 수행한다.
     */
    public String cleanDescription() {
        if (description == null) return "";
        String decoded = Parser.unescapeEntities(description, false);
        return decoded.replaceAll("<[^>]*>", "").trim();
    }
}
