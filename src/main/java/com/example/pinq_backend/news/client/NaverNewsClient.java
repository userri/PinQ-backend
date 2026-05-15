package com.example.pinq_backend.news.client;

import com.example.pinq_backend.config.properties.NaverNewsProperties;
import com.example.pinq_backend.news.dto.NaverNewsItem;
import com.example.pinq_backend.news.dto.NaverNewsResponse;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 네이버 뉴스 검색 API 클라이언트.
 *
 * GET https://openapi.naver.com/v1/search/news
 *   ?query={keyword}&display={count}&sort=date
 *
 * 인증: X-Naver-Client-Id / X-Naver-Client-Secret 헤더.
 */
@Slf4j
@Component
public class NaverNewsClient {

    private static final String BASE_URL = "https://openapi.naver.com/v1/search/news";

    private final RestClient restClient;

    public NaverNewsClient(NaverNewsProperties props) {
        this.restClient = RestClient.builder()
            .defaultHeader("X-Naver-Client-Id", props.clientId())
            .defaultHeader("X-Naver-Client-Secret", props.clientSecret())
            .build();
    }

    /**
     * 키워드로 최신 뉴스를 검색해 반환한다.
     *
     * @param keyword 검색 키워드 (예: "금리", "환율")
     * @param count   가져올 뉴스 개수 (1~100)
     * @return 뉴스 항목 목록. 오류 시 빈 리스트.
     */
    public List<NaverNewsItem> search(String keyword, int count) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                .queryParam("query", keyword)
                .queryParam("display", count)
                .queryParam("sort", "date")
                .build(false)
                .encode()
                .toUri();

            NaverNewsResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(NaverNewsResponse.class);

            if (response == null || response.items() == null) {
                return Collections.emptyList();
            }
            return response.items();
        } catch (Exception e) {
            log.error("네이버 뉴스 검색 실패. keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }
}
