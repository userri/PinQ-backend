package com.example.pinq_backend.user.repository;

import com.example.pinq_backend.user.domain.UserBookmark;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBookmarkRepository extends JpaRepository<UserBookmark, Long> {

    boolean existsByUserIdAndQuizId(Long userId, Long quizId);

    /** 사용자의 북마크 목록 — 최신순. */
    List<UserBookmark> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * (목록 응답에서 북마크 여부를 한 번에 채우기 위한) 배치 조회.
     * 주어진 quizId 들 중 사용자가 북마크해 둔 quizId 만 Set 으로 반환한다.
     */
    @Query("""
        SELECT b.quizId
        FROM UserBookmark b
        WHERE b.user.id = :userId
          AND b.quizId IN :quizIds
        """)
    Set<Long> findBookmarkedQuizIds(
        @Param("userId") Long userId,
        @Param("quizIds") Collection<Long> quizIds
    );

    @Modifying
    @Query("DELETE FROM UserBookmark b WHERE b.user.id = :userId AND b.quizId = :quizId")
    int deleteByUserIdAndQuizId(@Param("userId") Long userId, @Param("quizId") Long quizId);

    @Modifying
    @Query("DELETE FROM UserBookmark b WHERE b.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
