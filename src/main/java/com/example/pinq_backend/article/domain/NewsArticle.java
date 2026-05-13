package com.example.pinq_backend.article.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 퀴즈의 근거가 되는 뉴스 기사.
 *
 * Phase 2: DataInitializer 가 시드 4건을 넣어둔다.
 * Phase 3: 네이버 뉴스 API 로 매일 갱신.
 *
 * url 은 중복 저장을 막기 위해 unique 제약을 둔다.
 */
@Entity
@Table(
    name = "news_article",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_news_article_url", columnNames = "url")
    },
    indexes = {
        @Index(name = "idx_news_article_category", columnList = "category"),
        @Index(name = "idx_news_article_published_at", columnList = "published_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsArticle extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private Category category;

    @Builder
    private NewsArticle(
        String title,
        String url,
        String source,
        LocalDateTime publishedAt,
        Category category
    ) {
        this.title = title;
        this.url = url;
        this.source = source;
        this.publishedAt = publishedAt;
        this.category = category;
    }
}
