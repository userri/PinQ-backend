package com.example.pinq_backend.audit.repository;

import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizGenerationAttemptRepository
        extends JpaRepository<QuizGenerationAttempt, Long> {

    /**
     * 날짜 × 카테고리 × 회차 × stage × reason 롤업 — 검수 회차의 조회 패턴 그대로다.
     *
     * stage 와 reason 을 따로 묶는 것이 핵심이다. 한 축으로 뭉치면
     * "VERIFY 단계 손실이 전체의 몇 %인가"를 문자열 조작 없이는 못 센다.
     *
     * <p>{@code runWindow} 를 축에 넣는 이유: <b>밀린 날을 백필로 메우면 같은 슬롯이
     * 여러 번 돌아 시도 수가 배로 뛴다.</b> 2026-08-17 EXCHANGE_RATE 61건은 정기 22 /
     * 백필 39 였는데, 이 축이 없으면 그 스파이크를 기사 풀 악화로 오독한다.
     * 종전 링버퍼 집계 스크립트에는 이 구분이 있었으므로(다만 시각 문턱 추정이라
     * 정기 회차가 늦어지면 틀렸다) 빼고 갈아타면 기능 후퇴다.
     *
     * <p><b>사라진 퀴즈를 가리키는 발행 행은 뺀다.</b> {@code generateTodayQuizzes()} 는
     * 그날 퀴즈를 지우고 다시 만드는데 계측 행은 별도 트랜잭션으로 이미 커밋돼 있고 FK 도
     * 없다(계측이 본 데이터 삭제를 막으면 안 되므로 의도적이다). 그래서 재실행한 날은
     * {@code PUBLISHED} 행이 두 벌이 되고 오래된 쪽은 존재하지 않는 {@code quiz_id} 를
     * 가리킨다 — 그대로 세면 "그날만 발행이 두 배"인 행이 나와 손실률 분모가 틀린다.
     * {@code quizId is null} 을 함께 허용해야 탈락 행이 같이 날아가지 않는다.
     */
    @Query("""
            select a.occurredOn as day, a.category as category,
                   a.runWindow as runWindow,
                   a.stage as stage, a.reason as reason, count(a) as attempts,
                   (select count(distinct a2.articleUrl) from QuizGenerationAttempt a2
                     where a2.occurredOn = a.occurredOn
                       and a2.category = a.category) as distinctArticles
            from QuizGenerationAttempt a
            where a.occurredOn >= :from
              and (a.quizId is null
                   or exists (select 1 from Quiz q where q.id = a.quizId))
            group by a.occurredOn, a.category, a.runWindow, a.stage, a.reason
            order by a.occurredOn asc, a.category asc, a.runWindow asc, a.stage asc
            """)
    List<DailyRow> rollupSince(@Param("from") LocalDate from);

    /** 하루치 원시 행 — 기사 제목으로 기사 풀 오염을 눈으로 확인하는 경로 */
    List<QuizGenerationAttempt> findByOccurredOnOrderByOccurredAtAsc(LocalDate day);

    interface DailyRow {
        LocalDate getDay();
        String getCategory();
        /** REGULAR | BACKFILL */
        String getRunWindow();
        String getStage();
        String getReason();
        long getAttempts();

        /**
         * 그날 그 카테고리가 건드린 <b>서로 다른 기사 수</b>. 시도 ÷ 이 값이 재시도 배수다.
         *
         * <p>같은 날·같은 카테고리의 모든 행에 같은 값이 반복해서 실린다. 소비자가 롤업
         * 한 번만 읽고 배수를 계산할 수 있게 하려는 의도적 중복이다 — 별도 조회로 빼면
         * 검수 절차가 두 번 호출·두 번 파싱이 되고, 그 둘이 어긋나는 사고 경로가 생긴다.
         *
         * <p><b>제목이 아니라 URL 로 센다.</b> 종전 링버퍼 스크립트는 로그 줄의
         * {@code title=} 뒤 문자열을 키로 썼는데 그 뒤에 {@code stage=}·{@code reason=} 이
         * 붙어 있어, <b>같은 기사가 다른 단계로 떨어지면 다른 기사로 세어졌다</b>.
         * 그래서 기사 수는 부풀고 배수는 낮게 나왔다(8/17 EXCHANGE_RATE 고유 29건이 40 으로
         * 잡혀 2.10× 대신 1.53×). URL 은 그런 오염이 없다.
         *
         * <p>{@code articleUrl} 이 null 인 행(기사 없이 실패한 시도)은 세지 않는다 —
         * {@code count(distinct)} 가 null 을 빼므로 별도 조건이 필요 없다.
         */
        long getDistinctArticles();
    }
}
