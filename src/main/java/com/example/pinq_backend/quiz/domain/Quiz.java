package com.example.pinq_backend.quiz.domain;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.article.domain.NewsArticle;
import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
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
@Table(
    name = "quiz",
    indexes = @Index(name = "idx_quiz_date", columnList = "quiz_date")
)
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

    /**
     * 이 퀴즈의 카테고리. 생성 시 '출제 슬롯'의 카테고리를 그대로 저장하는 신뢰 원천.
     *
     * article.category 를 파생값으로 쓰지 않는 이유: 저장 시 findByUrl 로 기존 기사를
     * 재사용하면 기사의 '최초' 카테고리가 따라와 실제 출제 카테고리와 어긋날 수 있다.
     * (예: 부동산 슬롯이 예전 금리 기사를 재사용 → article=INTEREST_RATE 인데 문항은 부동산)
     *
     * nullable: 이 컬럼 도입 전 저장된 레코드는 null 일 수 있어 {@link #getCategory()} 가
     * article 로 폴백한다. 신규 생성분은 항상 채워진다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 32)
    private Category category;

    /** 이 퀴즈가 제공되는 날짜. null = Phase 2 시드 데이터 (날짜 무관). */
    @Column(name = "quiz_date")
    private LocalDate quizDate;

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
        Category category,
        LocalDate quizDate,
        String question,
        String explanation,
        String keyword,
        List<Choice> choices
    ) {
        validateChoices(choices);
        this.article = article;
        this.category = category;
        this.quizDate = quizDate;
        this.question = question;
        this.explanation = explanation;
        this.keyword = keyword;
        choices.forEach(c -> c.assignQuiz(this));
        this.choices = new ArrayList<>(choices);
    }

    /**
     * 이 퀴즈의 카테고리. 저장된 값이 있으면 그것을, 없으면(구 레코드) 기사에서 파생한다.
     * Lombok @Getter 대신 폴백 로직을 위해 직접 정의한다.
     */
    public Category getCategory() {
        if (category != null) return category;
        return article != null ? article.getCategory() : null;
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

    /** 주어진 choiceId 가 이 퀴즈의 보기 중 하나인지 확인. */
    public boolean hasChoice(Long choiceId) {
        return choices.stream()
            .anyMatch(c -> c.getId().equals(choiceId));
    }

    /** 주어진 choice id 가 이 퀴즈의 정답인지 판정. */
    public boolean isCorrectAnswer(Long choiceId) {
        return getAnswerChoice().getId().equals(choiceId);
    }
}
