package com.example.pinq_backend.article.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 뉴스 기사 카테고리.
 *
 * ERD 재정렬에 따라 Category 는 더 이상 Quiz 의 직접 속성이 아니라
 * NewsArticle 의 속성이다. 퀴즈는 자신이 참조하는 기사를 통해 카테고리를 얻는다.
 *
 * 프론트엔드의 Category enum 과 1:1 매칭된다.
 */
@Getter
@RequiredArgsConstructor
public enum Category {
    INTEREST_RATE("금리"),
    EXCHANGE_RATE("환율"),
    STOCK("증시"),
    REAL_ESTATE("부동산"),
    ;

    private final String displayName;
}
