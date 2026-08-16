package com.example.pinq_backend.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 퀴즈 생성 시도 1건 — 영속 계측.
 *
 * 발행 5개 뒤에는 탈락한 기사 수십 건이 있다(2026-08-12 환율 슬롯: 53회 시도).
 * 그 기록이 AuditLogBuffer(메모리 링버퍼)에만 있어 재시작·용량 초과로 사라졌고,
 * 8/15 에 검수가 하루 밀린 사이 그날치가 영구 소실됐다. 사람이 그날 안에 떠야
 * 남는 구조를 없애려고 테이블로 옮긴다.
 *
 * 사용자 노출 없음. 서비스 로직 어디서도 읽지 않으며 admin 조회 API 만 읽는다.
 *
 * <p>{@code occurredOn} 을 따로 두는 이유는 {@code TokenUsage} 와 같다 —
 * {@code DATE(occurred_at)} 로 묶으면 인덱스를 못 탄다.
 *
 * <p>{@code quizId} 는 발행 행에만 채운다. 검수 판정 등급(치명/경계/우수)을 나중에
 * 붙일 때 <b>이 컬럼이 결합의 열쇠</b>다 — 퀴즈는 DB 에 영구 보관되므로 과거분까지
 * 소급된다. FK 는 걸지 않는다: 재생성 시 그날 퀴즈를 지우는데 계측이 그걸 막으면 안 된다.
 */
@Entity
@Table(name = "quiz_generation_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizGenerationAttempt {

    /** 문자열 컬럼 상한 — 넘기면 저장이 예외로 죽고, 그 예외는 삼켜져 계측만 조용히 사라진다 */
    private static final int DETAIL_MAX = 255;
    private static final int TEXT_MAX = 512;
    private static final int KEYWORD_MAX = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 집계 키 — occurredAt 의 날짜 부분 */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    /** 출제 슬롯. 기사의 카테고리가 아니라 '어느 슬롯을 채우려던 시도인가' */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    /**
     * REGULAR | BACKFILL.
     *
     * 호출부가 명시적으로 넘긴다 — 시각으로 추측하면(종전 스크립트의 {@code hhmm < "06:10"})
     * 정기 회차가 늦어질 때 그대로 오분류된다.
     */
    @Column(name = "run_window", nullable = false, length = 16)
    private String runWindow;

    /** 이 기사를 물어온 검색어 — 검색 질의 축 개선의 근거 */
    @Column(name = "search_keyword", length = KEYWORD_MAX)
    private String searchKeyword;

    /** 기사 풀 오염을 눈으로 보는 축 (8/16 EXCHANGE_RATE 후보의 교보문고·이치방쿠지) */
    @Column(name = "article_title", length = TEXT_MAX)
    private String articleTitle;

    @Column(name = "article_url", length = TEXT_MAX)
    private String articleUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private AttemptStage stage;

    /** 발행 행은 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private AttemptReason reason;

    /** 룰베이스 사유 원문, LLM skipReason 등 */
    @Column(name = "detail", length = DETAIL_MAX)
    private String detail;

    /** 발행된 경우만 — 판정 등급 소급 결합의 열쇠 */
    @Column(name = "quiz_id")
    private Long quizId;

    public QuizGenerationAttempt(LocalDateTime occurredAt, String category, String runWindow,
                                 String searchKeyword, String articleTitle, String articleUrl,
                                 AttemptStage stage, AttemptReason reason, String detail,
                                 Long quizId) {
        this.occurredAt = occurredAt;
        this.occurredOn = occurredAt.toLocalDate();
        this.category = category;
        this.runWindow = runWindow;
        this.searchKeyword = truncate(searchKeyword, KEYWORD_MAX);
        this.articleTitle = truncate(articleTitle, TEXT_MAX);
        this.articleUrl = truncate(articleUrl, TEXT_MAX);
        this.stage = stage;
        this.reason = reason;
        this.detail = truncate(detail, DETAIL_MAX);
        this.quizId = quizId;
    }

    /**
     * UTF-16 단위(length)로 자른다. 경계가 서로게이트 쌍 중간이면(문자 하나가 high/low
     * 서로게이트 두 코드 유닛으로 되어 있는 이모지 등) 한 글자 더 줄인다 — 안 그러면 짝
     * 없는 서로게이트가 남아 MySQL utf8mb4 가 "Incorrect string value" 로 저장을 거부하고,
     * 그 예외는 recorder 가 삼키므로 자르기가 막으려던 바로 그 일(행 유실)이 벌어진다.
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        int cut = max;
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut);
    }
}
