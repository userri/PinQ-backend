package com.example.pinq_backend.audit.domain;

/**
 * 시도가 어느 단계에서 끝났는가.
 *
 * "왜"({@link AttemptReason})와 직교하게 둔다. 한 값에 뭉치면
 * (예: {@code verify_off_category}) 단계별 합을 보려고 문자열을 자르게 되고,
 * 그것이 종전 로그 파싱이 겪던 취약성이다.
 */
public enum AttemptStage {
    /** 기사를 LLM 에 넘기기 전 룰베이스 필터 */
    PREFILTER,
    /** 생성 LLM 호출 (SKIP 판정·파싱 실패·API 오류) */
    GENERATE,
    /** 저장 전 방어선 (룰베이스 검증 + 용어·유사도 가드) */
    VALIDATE,
    /** cross-model 검증 (Claude) */
    VERIFY,
    /** 발행 성공 — 탈락이 아니다. 분모를 이 값으로 센다 */
    PUBLISHED
}
