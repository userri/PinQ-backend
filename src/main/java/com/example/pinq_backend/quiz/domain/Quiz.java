package com.example.pinq_backend.quiz.domain;

import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 개의 4지선다 퀴즈.
 *
 * ERD 재정렬 결과:
 *  - category 는 더 이상 quiz 의 직접 속성이 아니라 article 로부터 파생된다.
 *  - 해설/기사 정보는 더 이상 @Embedded 가 아니라 별도 엔티티 {@link NewsArticle} 로 분리.
 *  - keyword 컬럼 신규 추가 (꼭 알아둘 단어 · 짧은 설명).
 *
 * 도메인 규칙:
 *  - choices 는 정확히 4개여야 한다.
 *  - 그 중 정확히 1개만 is_answer=true 이어야 한다.
 */
@Entity
@Table(name = "quiz")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Quiz extends BaseTimeEntity {

    private static final int REQUIRED_CHOICE_COUNT = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private NewsArticle article;

    @Column(name = "question", nullable = false, length = 1000)
    private String question;

    @Column(name = "explanation", nullable = false, length = 2000)
    private String explanation;

    /** 꼭 알아둘 단어 · 짧은 설명. 한 컬럼에 자유 텍스트로 저장. */
    @Column(name = "keyword", length = 500)
    private String keyword;

    @OneToMany(
        mappedBy = "quiz",
        cascade = CascadeType.ALL,
        orphanRemoval = true
    )
    @OrderBy("orderNum ASC")
    private List<Choice> choices = new ArrayList<>();

    @Builder
    private Quiz(
        NewsArticle article,
        String question,
        String explanation,
        String keyword,
        List<Choice> choices
    ) {
        validateChoices(choices);
        this.article = article;
        this.question = question;
        this.explanation = explanation;
        this.keyword = keyword;
        choices.forEach(c -> c.assignQuiz(this));
        this.choices = new ArrayList<>(choices);
    }

    private static void validateChoices(List<Choice> choices) {
        if (choices == null || choices.size() != REQUIRED_CHOICE_COUNT) {
            throw new IllegalArgumentException(
                "Choice 는 정확히 " + REQUIRED_CHOICE_COUNT + "개여야 합니다."
            );
        }
        long answerCount = choices.stream().filter(Choice::isAnswer).count();
        if (answerCount != 1L) {
            throw new IllegalArgumentException(
                "Choice 중 정확히 1개만 정답이어야 합니다. (현재 " + answerCount + "개)"
            );
        }
    }

    public List<Choice> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    /** 정답 Choice 를 반환. 도메인 불변식상 항상 존재한다. */
    public Choice getAnswerChoice() {
        return choices.stream()
            .filter(Choice::isAnswer)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "정답 Choice 가 없습니다. quizId=" + id
            ));
    }

    /** 주어진 choice id 가 이 퀴즈의 정답인지 판정. */
    public boolean isCorrectAnswer(Long choiceId) {
        return getAnswerChoice().getId().equals(choiceId);
    }
}
