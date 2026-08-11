package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 버퍼가 검수에 필요한 로그만 주워 담는지 본다.
 *
 * 실제 logback 루트에 붙여 {@code log.info} 호출을 그대로 흘린다 — 필터가 서비스 코드의
 * 문구와 어긋나면(예: 문구를 바꾼 커밋) 여기서 깨져야 하기 때문이다.
 */
class AuditLogBufferTest {

    private static final Logger WATCHED_LOGGER =
            LoggerFactory.getLogger(AuditLogBufferTest.class);

    private AuditLogBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new AuditLogBuffer(Clock.system(ZoneId.of("Asia/Seoul")));
        buffer.attach();
        buffer.clear();
    }

    @Test
    @DisplayName("생성 파이프라인 판정 로그만 담고 무관한 로그는 버린다")
    void keepsOnlyPipelineVerdicts() {
        WATCHED_LOGGER.info("기사 건너뜀 (SKIP 또는 생성 실패). category=STOCK");
        WATCHED_LOGGER.info("최근 7일 내 동일 keyword 용어 재출제로 폐기. term=환율");
        WATCHED_LOGGER.info("사용자가 로그인했습니다");

        assertThat(buffer.recent(1, null))
                .extracting(AuditLogBuffer.Entry::message)
                .anyMatch(m -> m.contains("건너뜀"))
                .anyMatch(m -> m.contains("폐기"))
                .noneMatch(m -> m.contains("로그인"));
    }

    @Test
    @DisplayName("level 을 주면 그 이상만 남는다")
    void filtersByLevel() {
        WATCHED_LOGGER.info("퀴즈 생성 완료. 성공=5/5");
        WATCHED_LOGGER.warn("OpenAI 응답 유효성 검증 실패. title=x");

        assertThat(buffer.recent(1, Level.WARN))
                .extracting(AuditLogBuffer.Entry::message)
                .containsExactly("OpenAI 응답 유효성 검증 실패. title=x");
    }

    @Test
    @DisplayName("보관 한도를 넘으면 오래된 것부터 버린다")
    void evictsOldestBeyondCapacity() {
        for (int i = 0; i < 600; i++) {
            WATCHED_LOGGER.info("퀴즈 생성 완료. seq={}", i);
        }

        var entries = buffer.recent(1, null);
        assertThat(entries).hasSize(500);
        assertThat(entries.get(0).message()).isEqualTo("퀴즈 생성 완료. seq=100");
    }
}
