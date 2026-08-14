package com.example.pinq_backend.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * LLM 호출 1회의 토큰 사용량 — 영속 계측.
 *
 * 왜 테이블인가: 종전에는 {@code TokenUsageLogger} 가 만든 한 줄이 로그로만 나갔고,
 * 그 로그는 {@code AuditLogBuffer}(메모리 링버퍼)에만 남아 **배포·재시작마다 사라졌다.**
 * 2026-08-14 검수에서 프롬프트 캐싱 적중(verify 12회 중 read 11)을 처음 계측했는데,
 * 그 수치를 다음 날과 비교할 방법이 없다는 것이 확인돼 영속화한다.
 * 로그 한 줄은 그대로 유지한다 — 실시간 확인 경로를 없애려는 게 아니다.
 *
 * 사용자 노출 없음. 서비스 로직 어디서도 읽지 않으며 admin 조회 API 만 읽는다.
 *
 * <p>{@code occurredOn} 을 따로 두는 이유: 날짜별 집계가 이 테이블의 유일한 읽기 패턴인데,
 * {@code DATE(occurred_at)} 로 묶으면 인덱스를 못 타고 H2/MySQL 함수 차이도 탄다.
 */
@Entity
@Table(name = "token_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 호출 시각(KST) */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 집계 키 — occurredAt 의 날짜 부분 */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    /** generate | verify — 어느 단계가 토큰을 먹는지 가르는 축 */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /** 응답이 알려준 실제 모델. 모델 교체 전후 비용 비교의 유일한 근거라 함께 남긴다 */
    @Column(name = "model", length = 128)
    private String model;

    /** 캐시에 걸리지 않은 입력 잔여분 — 이 값의 감소를 절감으로 읽으면 안 된다 */
    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "cache_write_tokens", nullable = false)
    private int cacheWriteTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    private int cacheReadTokens;

    /** prompt + completion + cache_write + cache_read (응답이 total 을 주면 그 값) */
    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    public TokenUsage(LocalDateTime occurredAt, String kind, String model,
                      int promptTokens, int completionTokens,
                      int cacheWriteTokens, int cacheReadTokens, int totalTokens) {
        this.occurredAt = occurredAt;
        this.occurredOn = occurredAt.toLocalDate();
        this.kind = kind;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.totalTokens = totalTokens;
    }
}
