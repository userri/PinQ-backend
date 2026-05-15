package com.example.pinq_backend.quiz.scheduler;

import com.example.pinq_backend.quiz.service.QuizGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 6시(KST)에 오늘의 퀴즈를 자동 생성하는 스케줄러.
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
     * 네이버 API → Claude API → DB 저장까지 수초~수십초 소요.
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
}
