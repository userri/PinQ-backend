package com.example.pinq_backend.quiz.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 퀴즈 생성 dry-run 결과 아카이브 — "프롬프트 실험 로그".
 *
 * 실데이터(quiz 테이블)와 완전히 분리된 실험 전용 테이블이다.
 * 룰 실험(extraRules 주입) 전후의 품질을 비교 분석할 수 있도록,
 * 어떤 임시 룰이 적용된 상태에서 생성됐는지까지 함께 저장한다.
 *
 * 사용자 노출 없음 — admin dry-run API 만 기록하며, 서비스 로직 어디서도 읽지 않는다.
 */
@Entity
@Table(name = "trial_quiz")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrialQuiz extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    /** 파이프라인 통과 여부 (false = 모든 후보 소진) */
    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "candidates_tried", nullable = false)
    private int candidatesTried;

    @Column(name = "question", length = 1000)
    private String question;

    /** 보기 JSON 직렬화 (orderNum/content/answer) — 실험 로그라 정규화하지 않는다 */
    @Lob
    @Column(name = "choices_json", length = 65535) // MySQL TEXT 와 validate 정합
    private String choicesJson;

    @Column(name = "explanation", length = 2000)
    private String explanation;

    @Column(name = "keyword", length = 500)
    private String keyword;

    @Column(name = "article_title", length = 500)
    private String articleTitle;

    @Column(name = "article_url", length = 1000)
    private String articleUrl;

    /** 실험용 임시 생성 규칙 (없으면 null = 현행 프롬프트 그대로) */
    @Lob
    @Column(name = "extra_gen_rules", length = 65535)
    private String extraGenRules;

    /** 실험용 임시 검증 기준 (없으면 null) */
    @Lob
    @Column(name = "extra_verify_rules", length = 65535)
    private String extraVerifyRules;

    /** 실험용 생성 모델 오버라이드 (없으면 null = 운영 기본 모델) */
    @Column(name = "model", length = 64)
    private String model;

    public static TrialQuiz record(
            String category, boolean success, int candidatesTried,
            String question, String choicesJson, String explanation, String keyword,
            String articleTitle, String articleUrl,
            String extraGenRules, String extraVerifyRules, String model
    ) {
        TrialQuiz t = new TrialQuiz();
        t.category = category;
        t.success = success;
        t.candidatesTried = candidatesTried;
        t.question = question;
        t.choicesJson = choicesJson;
        t.explanation = explanation;
        t.keyword = keyword;
        t.articleTitle = articleTitle;
        t.articleUrl = articleUrl;
        t.extraGenRules = extraGenRules;
        t.extraVerifyRules = extraVerifyRules;
        t.model = model;
        return t;
    }
}
