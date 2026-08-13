package com.example.pinq_backend.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 퀴즈 생성 파이프라인의 판정 로그를 메모리에 보관한다 — 검수 조회용.
 *
 * 왜 필요한가: 검수 루틴은 종전에 SSH 로 붙어 {@code docker logs | grep} 을 돌렸다.
 * 그 경로가 막히는 네트워크가 있어(아웃바운드 22 차단) HTTPS 로 같은 정보를 볼 수 있게
 * 옮긴다. 필터 정규식은 종전 grep 패턴과 같은 집합을 겨냥한다.
 *
 * ⚠️ 휘발성이다 — 컨테이너가 재시작하면 비워진다. 배포는 blue/green 이라 배포 시각
 * 이전의 생성 로그는 조회되지 않는다. 아침 발행(06시) 후 그날 배포가 없으면 남아 있다.
 * 영속이 필요해지면 그때 테이블로 승격한다 (지금은 당일 검수만이 소비자다).
 *
 * DB 에 남는 발행 결과와 달리 이건 "왜 안 뽑혔나"(SKIP·폐기 사유)를 담는다 —
 * 발행 수가 5/5 를 못 채운 날 원인을 가르는 유일한 단서라 검수의 필수 축이다.
 */
@Component
public class AuditLogBuffer extends AppenderBase<ILoggingEvent> {

    /**
     * 보관 개수.
     *
     * 500 은 SKIP·폐기 줄만 담던 시절의 수치였고 8/12 에 이미 331/500 을 썼다.
     * token-usage 를 받기 시작하면 LLM 호출 1건당 한 줄이 더 붙어 넘칠 여지가 커진다.
     * 넘치면 오래된 것부터, 즉 정기 06:04 회차부터 조용히 밀려나 시도 횟수가 적게 잡힌다
     * — 결손이 아니라 "적게 실패한 날"로 오독되는 게 이 버퍼의 가장 나쁜 실패 모드다.
     * 문자열 한 줄짜리 엔트리라 3000 칸도 메모리 부담이 아니다.
     */
    private static final int CAPACITY = 3000;

    private static final String WATCHED_PACKAGE = "com.example.pinq_backend";

    /**
     * 종전 {@code grep -iE "SKIP|건너뜀|폐기|검증 실패|생성 완료|백필|중복"} 과 같은 집합에
     * {@code token-usage} 를 더한 것. 여기를 더 넓히면 무관한 로그가 섞여 검수자가 신호를 놓친다.
     *
     * token-usage 를 넣은 이유: 이 회선은 아웃바운드 22 가 막혀 {@code docker logs} 로 갈 수 없어
     * 이 버퍼가 유일한 관측 창이다. 8/13 프롬프트 캐싱 배포 직후 cache_read 를 확인하려다
     * 문구가 안 걸려 계측 자체가 불가능했다 — 계측 수단을 배포하고 볼 수 없으면 없는 것과 같다.
     */
    private static final Pattern WATCHED = Pattern.compile(
            "SKIP|건너뜀|폐기|검증 실패|생성 완료|백필|중복|token-usage", Pattern.CASE_INSENSITIVE);

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private final Clock clock;

    public AuditLogBuffer(Clock clock) {
        this.clock = clock;
    }

    /** 한 건의 판정 로그. */
    public record Entry(LocalDateTime at, String level, String logger, String message) {}

    @PostConstruct
    void attach() {
        setName("auditLogBuffer");
        // Spring 이 아니라 logback 루트 로거에 직접 붙인다 — 서비스 코드를 건드리지 않고
        // 기존 log.info 호출을 그대로 주워 담기 위해서다.
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        org.slf4j.Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        start();
        root.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLoggerName().startsWith(WATCHED_PACKAGE)) return;
        String message = event.getFormattedMessage();
        if (message == null || !WATCHED.matcher(message).find()) return;

        Entry entry = new Entry(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), clock.getZone()),
                event.getLevel().toString(),
                shortLoggerName(event.getLoggerName()),
                message);

        // append 는 여러 스레드에서 동시에 불린다 (생성은 카테고리별 순차지만 스케줄러·
        // 웹 요청이 섞인다). ArrayDeque 는 비동기 안전이 아니라 락이 필요하다.
        synchronized (entries) {
            if (entries.size() >= CAPACITY) entries.removeFirst();
            entries.addLast(entry);
        }
    }

    /** 최근 {@code hours} 시간 내 항목을 오래된 순으로 반환한다. */
    public List<Entry> recent(int hours, Level minLevel) {
        LocalDateTime since = LocalDateTime.now(clock).minusHours(hours);
        synchronized (entries) {
            List<Entry> result = new ArrayList<>();
            for (Entry e : entries) {
                if (e.at().isBefore(since)) continue;
                if (minLevel != null && Level.toLevel(e.level()).toInt() < minLevel.toInt()) continue;
                result.add(e);
            }
            return result;
        }
    }

    /** 테스트용 — 보관분을 비운다. */
    void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static String shortLoggerName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx < 0 ? fqcn : fqcn.substring(idx + 1);
    }
}
