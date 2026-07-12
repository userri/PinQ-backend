package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 사설·칼럼 제목 룰베이스 차단 검증.
 * 2026-07-12 실사고 표본([기자의 눈] 칼럼이 퀴즈로 발행)과 통과해야 할 일반 기사를 함께 검사.
 */
class EditorialTitleFilterTest {

    private boolean isEditorial(String title) throws Exception {
        Method m = QuizGenerationService.class.getDeclaredMethod("isEditorialTitle", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, title);
    }

    @Test
    void 오피니언_코너명은_차단된다() throws Exception {
        assertThat(isEditorial("[기자의 눈] 농식품부 장관인가, 물가안정부 장관인가")).isTrue();
        assertThat(isEditorial("[사설]하이닉스 나스닥 '역사적 데뷔'… 韓 자본시장 선진화 계기로")).isTrue();
        assertThat(isEditorial("[사설]주담대 죄는 은행들…청년·무주택자 숨통 틔울 대책 내놔야")).isTrue();
        assertThat(isEditorial("[데스크 칼럼] 금리 인하의 함정")).isTrue();
        assertThat(isEditorial("[김부장의 시선] 환율 방어전")).isTrue();
        assertThat(isEditorial("사설: 물가와 민생")).isTrue();
        assertThat(isEditorial("[기고] 부동산 세제 개편 방향")).isTrue();
    }

    @Test
    void 일반_기사는_통과한다() throws Exception {
        assertThat(isEditorial("한국은행, 16일 기준금리 인상 유력…관건은 '얼마나 더 올리나'")).isFalse();
        assertThat(isEditorial("치솟는 원둣값, 카페인·불면증 부담에...요즘 대세는 '대체 커피'")).isFalse();
        assertThat(isEditorial("\"고삐 풀린 가계대출\" 5대은행 총량 80% 소진…하반기 대출 더 막힌다")).isFalse();
        // 대괄호가 있어도 코너명이 아니면 통과
        assertThat(isEditorial("[7월 둘째 주 세계경제동향 브리핑] 코스피 급락")).isFalse();
        assertThat(isEditorial("[단독] 정부, 물가 대책 발표")).isFalse();
    }
}
