package com.example.pinq_backend.quiz.scheduler;

import com.example.pinq_backend.quiz.service.QuizGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 6시(KST)에 오늘의 퀴즈를 자동 생성하는 스케줄러.
 *
 * 06:00 발행은 의도된 제품 컨셉이다 — 새벽 미국장 마감·아침 뉴스까지 반영한
 * "오늘 아침의 최신 경제 퀴즈"를 만들고, 앱 홈 화면 문구("매일 오전 6시 발송",
 * "내일 오전 6시에 새 퀴즈가 도착해요")와 일치한다. 자정~6시에 퀴즈가 없는 것은
 * 버그가 아니라 발행 전 상태다.
 *
 * Clock Bean이 Asia/Seoul로 고정되어 있고,
 * 스케줄러 cron의 zone도 동일하게 맞춰 타임존 불일치를 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizGenerationScheduler {

    private final QuizGenerationService quizGenerationService;

    /**
     * 매일 오전 6시 KST 자동 실행.
     * 네이버 API → OpenAI API → DB 저장까지 수초~수십초 소요.
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void generateDailyQuizzes() {
        log.info("[스케줄러] 일별 퀴즈 자동 생성 시작");
        try {
            int count = quizGenerationService.generateTodayQuizzes();
            log.info("[스케줄러] 완료. 생성된 퀴즈 수={}", count);
        } catch (Exception e) {
            log.error("[스케줄러] 퀴즈 생성 중 오류 발생", e);
        }
    }

    /**
     * 발행 시각 이후(06:10~23:10) 매시간 10분에 오늘 퀴즈 존재 여부를 확인하고,
     * 없으면 생성한다.
     *
     * 정기 생성 시각(06:00)에 서버가 내려가 있었거나(배포/재시작),
     * 네이버·OpenAI 장애로 생성이 실패한 날을 자동 복구하는 가드.
     * 오늘 퀴즈가 이미 있으면 아무 일도 하지 않으며, 06시 이전에는 돌지 않아
     * "아침 6시 발행" 컨셉을 침범하지 않는다.
     */
    @Scheduled(cron = "0 10 6-23 * * *", zone = "Asia/Seoul")
    public void ensureTodayQuizzes() {
        try {
            int count = quizGenerationService.ensureTodayQuizzes();
            if (count > 0) {
                log.info("[가드] 누락됐던 오늘 퀴즈 생성 완료. 생성 수={}", count);
            }
        } catch (Exception e) {
            log.error("[가드] 오늘 퀴즈 확인/생성 중 오류", e);
        }
    }
}
