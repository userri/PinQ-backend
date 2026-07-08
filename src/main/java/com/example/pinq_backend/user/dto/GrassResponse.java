package com.example.pinq_backend.user.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 연간 잔디밭 (GitHub contribution graph 스타일).
 *
 * days 는 활동이 있는 날만 담는 sparse 목록 — 빈 칸은 클라이언트가 채운다.
 * (365일 × 대부분 0 인 배열을 내려보내는 것보다 페이로드가 훨씬 작다)
 *
 * level 의미 (하루 기본 세트 = 4문제 기준):
 *   1 = 1~2문제 풀이, 또는 그날 신규 학습 없이 복습만 함 (연한 잔디)
 *   2 = 3문제
 *   3 = 4문제 완주
 *   4 = 4문제 완주 + 전부 정답 — "만점 잔디" (브랜드 라임으로 표시)
 *
 * 잔디 농도는 '신규 학습'의 지표다: 복습을 아무리 많이 해도 그 자체로는
 * level 1 을 넘지 않는다. 복습의 장기 성과는 {@code graduatedTrees} 로 표현된다.
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
     * @param solved   그날 첫 시도한 문제 수 (복습 제외)
     * @param correct  그날 첫 시도 정답 수
     * @param reviewed 그날 복습한 문제 수 (level 에는 영향 없음 — 툴팁 표시용)
     * @param level    잔디 농도 1~4
     */
    public record GrassDay(
        LocalDate date,
        int solved,
        int correct,
        int reviewed,
        int level
    ) {}
}
