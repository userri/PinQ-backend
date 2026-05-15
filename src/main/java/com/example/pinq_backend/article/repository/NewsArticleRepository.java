package com.example.pinq_backend.article.repository;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    List<NewsArticle> findAllByCategoryOrderByPublishedAtDesc(Category category);

    /** URL 중복 방지용 조회 (uk_news_article_url). */
    Optional<NewsArticle> findByUrl(String url);
}
