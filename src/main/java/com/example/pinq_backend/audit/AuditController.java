package com.example.pinq_backend.audit;

import ch.qos.logback.classic.Level;
import com.example.pinq_backend.audit.dto.AuditQuizResponse;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일일 퀴즈 검수용 읽기 전용 조회 API (admin — AdminAuthFilter 가 X-Admin-Secret 검사).
 *
 * 종전에는 {@code ~/bin/pinq-quiz-fetch.sh} 가 SSH 로 붙어 MySQL SELECT 와 docker logs 를
 * 읽었다. 그 경로는 머신마다 키 배포가 필요했고, 아웃바운드 22 를 막는 네트워크에서는
 * 키가 있어도 붙지 못해 8/7 검수가 통째로 빠졌다. 여기로 옮기면 HTTPS(443) 한 축만 쓴다.
 *
 * 대응: {@code quizzes} → GET /quizzes · {@code counts} → GET /counts · {@code logs} → GET /logs.
 *
 * ⚠️ 공개 금지. 응답에 정답(is_answer)이 실린다 — 날짜별로 뽑아주면 치트시트가 되고
 * 정답률 밴드(55~75%) 추적이 무의미해진다.
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final int MAX_COUNT_DAYS = 90;
    private static final int MAX_LOG_HOURS = 168;

    private final QuizRepository quizRepository;
    private final AuditLogBuffer auditLogBuffer;
    private final Clock clock;

    /**
     * 특정 날짜 발행분 전문 + 선지(정답 포함).
     *
     * @param date 생략 시 오늘(KST). 백필 검수는 날짜를 명시한다.
     */
    @GetMapping("/quizzes")
    public List<AuditQuizResponse> quizzes(
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate target = date != null ? date : LocalDate.now(clock);
        return quizRepository.findAllByQuizDateOrderByIdAsc(target).stream()
                .map(AuditQuizResponse::from)
                .toList();
    }

    /** 날짜별 발행 수 — 룰 강화의 부작용(발행 수 감소)을 보는 축. */
    @GetMapping("/counts")
    public List<QuizRepository.PublishCountRow> counts(
        @RequestParam(name = "days", defaultValue = "10") int days
    ) {
        return quizRepository.findRecentPublishCounts(clamp(days, 1, MAX_COUNT_DAYS));
    }

    /**
     * 생성 파이프라인의 SKIP·폐기·검증 실패 판정 로그.
     *
     * 발행 수가 모자란 날 "왜 안 뽑혔나"를 가르는 단서다. 메모리 보관이라
     * 컨테이너 재시작(배포) 이전 기록은 남지 않는다 — {@link AuditLogBuffer} 참고.
     *
     * @param level 생략 시 전부. WARN 을 주면 실패 계열만 좁혀 본다.
     */
    @GetMapping("/logs")
    public List<AuditLogBuffer.Entry> logs(
        @RequestParam(name = "hours", defaultValue = "30") int hours,
        @RequestParam(name = "level", required = false) String level
    ) {
        Level minLevel = level == null || level.isBlank() ? null : Level.toLevel(level, Level.TRACE);
        return auditLogBuffer.recent(clamp(hours, 1, MAX_LOG_HOURS), minLevel);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
