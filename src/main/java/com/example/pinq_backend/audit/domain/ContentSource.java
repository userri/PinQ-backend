package com.example.pinq_backend.audit.domain;

/**
 * 생성에 쓴 기사 본문이 어디서 왔는가.
 *
 * 파이프라인은 항상 두 단계다 — 네이버 뉴스 검색 API 로 목록(제목·link·description 150자)을
 * 받고, {@code link} 가 news.naver.com 이면 본문을 스크래핑(앞 2000자)한다. 스크래핑이
 * 실패하면 API 의 description 으로 폴백한다. 그래서 "API vs 크롤링" 비율이란
 * <b>스크래핑 성공 : description 폴백</b> 이다.
 *
 * <p>이 비율은 종전에 어디에도 남지 않았다. 스크래퍼 로그 줄("스크래핑 성공/실패")이
 * {@code AuditLogBuffer} 의 키워드 패턴에 걸리지 않아 링버퍼에도 없었고, 서버 {@code docker logs}
 * 는 아웃바운드 22 가 막힌 회선에서 볼 수 없다(2026-09-17 확인). 패턴을 넓히는 대신 행에
 * 박는 이유: 링버퍼는 재시작·용량 초과로 사라지고, 비율은 날짜 간 비교가 목적이라 영속이어야 한다.
 *
 * <p>기사를 고르기 전 단계(PREFILTER: 사설·교차 사용)의 행은 본문을 확보하지 않았으므로 null.
 * 이 컬럼 배포(2026-09-17) 이전 행도 null 이다.
 */
public enum ContentSource {
    /** news.naver.com 본문 스크래핑 성공 (Jsoup, 앞 2000자) */
    SCRAPED,
    /**
     * 검색 API 의 description(~150자 스니펫)으로 폴백.
     * 스크래핑 실패(타임아웃·selector 불일치)와 non-naver URL 을 구분하지 않는다 — 둘 다
     * "본문 없이 스니펫으로 생성했다"는 같은 사실이고, 원인은 해당 회차 로그로 본다.
     * {@code EMPTY_CONTENT} 행(스니펫마저 비어 있음)도 여기 속한다.
     */
    DESCRIPTION
}
