package com.example.pinq_backend.audit.domain;

/**
 * 시도가 끝난 사유. {@link AttemptStage} 와 짝을 이룬다.
 *
 * 발행({@code PUBLISHED})은 사유가 없다 — null 로 둔다.
 */
public enum AttemptReason {
    /** 사설·칼럼 제목 룰 */
    EDITORIAL,
    /** 이번 사이클의 다른 카테고리가 이미 쓴 기사 */
    CROSS_CATEGORY_USED,
    /** 본문 스크래핑과 description 폴백이 모두 비었다 */
    EMPTY_CONTENT,
    /** 생성 LLM 이 "이 기사로는 못 만든다"고 판정 (skipReason 은 detail 에) */
    LLM_SKIP,
    /** 응답 JSON 파싱 실패 */
    PARSE_FAILED,
    /** API 호출 자체가 예외 */
    API_ERROR,
    /** 필수 필드 누락 등 응답 형태 불량 */
    INVALID_RESPONSE,
    /** 룰베이스 검증 반려 (사유 원문은 detail 에) */
    RULE_REJECTED,
    /** keyword 용어가 카테고리 표시명과 글자까지 같다 */
    TERM_EQUALS_CATEGORY,
    /** 최근 N일 내 같은 keyword 용어 재출제 */
    TERM_REUSE_GUARD,
    /** 최근 이력과 렉시컬 유사 */
    LEXICAL_DUPLICATE,
    /**
     * cross-model 검증 반려.
     *
     * ⚠️ 세부 사유(기준 16 인지 복수 정답인지)는 <b>알 수 없다</b> — verifyAnswer 가
     * boolean 만 돌려주기 때문이다. 응답 형식 변경은 캐시된 고정부를 건드리는 일이라
     * 별건으로 분리했다(docs/PENDING.md).
     */
    VERIFY_FAILED
}
