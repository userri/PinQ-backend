package com.example.pinq_backend.news.client;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 네이버 뉴스 페이지에서 기사 본문을 스크래핑한다.
 *
 * <p>네이버 뉴스 API의 {@code link} 필드(news.naver.com URL)를 대상으로 하며,
 * 네이버 자체 페이지는 HTML 구조가 일정하므로 selector 하나로 처리 가능하다.</p>
 *
 * <p>스크래핑 실패(타임아웃, 파싱 오류, non-naver URL 등) 시 {@link Optional#empty()}를 반환한다.
 * 호출측에서 반드시 description 폴백 처리를 해야 한다.</p>
 */
@Slf4j
@Component
public class NaverArticleScraper {

    /** 연결 타임아웃 (ms) */
    private static final int TIMEOUT_MS = 5_000;

    /**
     * Claude에 넘길 최대 글자 수.
     * 기사 본문이 너무 길면 토큰 낭비이므로 앞부분 2000자만 사용.
     */
    private static final int MAX_CHARS = 2_000;

    /**
     * 네이버 뉴스 URL에서 기사 본문을 추출한다.
     *
     * @param naverNewsUrl 네이버 뉴스 API의 {@code link} 필드값 (news.naver.com)
     * @return 본문 텍스트. 실패 시 {@link Optional#empty()}
     */
    public Optional<String> scrape(String naverNewsUrl) {
        if (naverNewsUrl == null || !naverNewsUrl.contains("news.naver.com")) {
            log.debug("네이버 뉴스 URL 아님, 스크래핑 생략. url={}", naverNewsUrl);
            return Optional.empty();
        }

        try {
            Document doc = Jsoup.connect(naverNewsUrl)
                // 일반 브라우저처럼 보이게 해 차단 방지
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .timeout(TIMEOUT_MS)
                .get();

            // 네이버 뉴스 본문 selector (우선순위 순)
            String body = doc.select("#dic_area").text();
            if (body.isBlank()) {
                body = doc.select("#newsct_article").text();
            }
            if (body.isBlank()) {
                log.warn("본문 selector 매칭 실패. url={}", naverNewsUrl);
                return Optional.empty();
            }

            // 너무 길면 앞부분만 사용
            String trimmed = body.length() > MAX_CHARS
                ? body.substring(0, MAX_CHARS)
                : body;

            log.info("스크래핑 성공. url={}, 글자수={}", naverNewsUrl, trimmed.length());
            return Optional.of(trimmed);

        } catch (Exception e) {
            log.warn("스크래핑 실패, description으로 폴백. url={}, error={}", naverNewsUrl, e.getMessage());
            return Optional.empty();
        }
    }
}
