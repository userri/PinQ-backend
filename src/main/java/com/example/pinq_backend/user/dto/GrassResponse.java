package com.example.pinq_backend.user.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 연간 잔디밭 (GitHub contribution graph 스타일).
 *
 * days 는 활동이 있는 날만 담는 sparse 목록 — 빈 칸은 클라이언트가 채운다.
 * (365일 × 대부분 0 인 배열을 내려보내는 것보다 페이로드가 훨씬 작다)
 *
 * level 의미 — **그날 맞힌 문제 수** 한 축으로만 정해진다:
 *   1 = 0~1문제 정답 (푼 날·복습만 한 날 포함, 연한 잔디)
 *   2 = 2문제 정답
 *   3 = 3문제 정답
 *   4 = 4문제 이상 정답 — 최고 등급 (브랜드 라임으로 표시)
 *
 * 문제의 출처(그날 발행분/밀린 과거 문제)를 따지지 않는다. 발행 수 보정,
 * 완주 여부, "전부 정답" 조건은 모두 없다 — 예외 규칙이 누적되며 설명 불가능해지던
 * 것을 "하루에 4문제 이상 맞히면 라임" 한 줄로 단순화했다 (2026-07-27).
 * 부수 효과로 밀린 문제를 나중에 푸는 행동이 동일하게 보상되고, 늦은 백필이
 * 기존 등급을 소급 강등시키지 않는다.
 *
 * 잔디 농도는 '신규 학습(첫 시도)'의 지표다: 복습은 첫 시도로 집계되지 않으므로
 * 복습만 한 날은 정답 0 → level 1 이다. 복습의 장기 성과는 {@code graduatedTrees} 로 표현된다.
 *
 * 스트릭과 잔디의 구분:
 *   - 스트릭 = 데일리 퀴즈를 푼 연속일. 복습만 한 날은 스트릭이 이어지지 않는다.
 *   - 잔디   = 학습 흔적. 복습만 한 날도 연한 잔디가 심어진다.
 *   이 둘은 의도적으로 다른 축이다.
 *
 * @param from            잔디 시작일 (오늘 - 364일)
 * @param to              오늘
 * @param totalActiveDays 기간 내 활동일 수 (퀴즈 시도한 날 ∪ 복습만 한 날)
 * @param perfectDays     기간 내 만점 완주일 수 (level 4)
 * @param currentStreak   현재 연속 학습일 (데일리 퀴즈 기준)
 * @param maxStreak       최고 연속 학습일
 * @param graduatedTrees  복습을 졸업한 문제 수 — "잔디밭에 키운 나무"
 */
public record GrassResponse(
    LocalDate from,
    LocalDate to,
    int totalActiveDays,
    int perfectDays,
    int currentStreak,
    int maxStreak,
    int graduatedTrees,
    List<GrassDay> days
) {
    /**
     * @param solved   그날 첫 시도한 문제 수 (복습 제외). level 에는 영향 없음 — 툴팁 표시용
     * @param correct  그날 첫 시도 정답 수 — **level 을 결정하는 유일한 값**
     * @param reviewed 그날 복습한 문제 수 (level 에는 영향 없음 — 툴팁 표시용)
     * @param level    잔디 농도 1~4 = min(max(correct, 1), 4)
     */
    public record GrassDay(
        LocalDate date,
        int solved,
        int correct,
        int reviewed,
        int level
    ) {}
}
