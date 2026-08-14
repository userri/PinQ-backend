package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.audit.repository.TokenUsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 영속 계측이 실제로 남고, 검수가 쓰는 롤업 모양으로 읽히는지 고정한다.
 *
 * 로그 문자열만 검증하던 종전 테스트(TokenUsageLoggerTest)로는 "재시작하면 사라진다"는
 * 문제를 못 잡았다 — 저장 경로는 저장 경로대로 테스트가 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TokenUsageRecorderTest {

    @Autowired
    private TokenUsageRecorder recorder;

    @Autowired
    private TokenUsageRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 저장된_사용량이_날짜_kind_로_롤업된다() {
        repository.deleteAll();

        recorder.record("verify", "claude-opus-4-8", 3400, 105, 2798, 0, 6303);
        recorder.record("verify", "claude-opus-4-8", 3391, 135, 0, 2798, 6324);
        recorder.record("generate", "gpt-4.1-mini", 5410, 89, 0, 0, 5499);

        List<TokenUsageRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        assertThat(rows).hasSize(2);

        var generate = rows.stream().filter(r -> r.getKind().equals("generate")).findFirst().orElseThrow();
        assertThat(generate.getCalls()).isEqualTo(1);
        assertThat(generate.getCacheHits()).isZero();

        var verify = rows.stream().filter(r -> r.getKind().equals("verify")).findFirst().orElseThrow();
        assertThat(verify.getCalls()).isEqualTo(2);
        // 캐싱 성과 지표는 토큰 합이 아니라 이 카운트다 — read>0 인 회차만 센다.
        assertThat(verify.getCacheHits()).isEqualTo(1);
        assertThat(verify.getCacheReadTokens()).isEqualTo(2798);
        assertThat(verify.getTotalTokens()).isEqualTo(6303 + 6324);
    }

    /** 계측 실패가 본 기능을 깨면 안 된다 — 저장이 터져도 예외가 호출부로 새지 않는다. */
    @Test
    void 저장_실패는_삼킨다() {
        recorder.record("verify", null, -1, -1, -1, -1, -1);
    }
}
