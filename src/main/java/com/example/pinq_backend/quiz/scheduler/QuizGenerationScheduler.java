package com.example.pinq_backend.quiz.scheduler;

import com.example.pinq_backend.quiz.service.QuizGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 자정 직후(00:05 KST)에 오늘의 퀴즈를 자동 생성하는 스케줄러.
 *
 * 00:05 인 이유: 날짜가 바뀌면 곧바로 새 퀴즈를 풀 수 있어야 하기 때문.
 * (기존에는 06:00 생성이라 자정~6시 사이에 그날 퀴즈가 아예 존재하지 않아
 *  새벽 사용자가 퀴즈를 풀 수 없었다. 정각 대신 5분 버퍼를 둬
 *  자정 경계의 날짜 레이스를 피한다.)
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
     * 매일 00:05 KST 자동 실행.
     * 네이버 API → OpenAI API → DB 저장까지 수초~수십초 소요.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
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
     * 매시간 10분에 오늘 퀴즈 존재 여부를 확인하고, 없으면 생성한다.
     *
     * 정기 생성 시각(00:05)에 서버가 내려가 있었거나(배포/재시작),
     * 외부 API 장애로 생성이 실패한 날을 자동 복구하는 가드.
     * 오늘 퀴즈가 이미 있으면 아무 일도 하지 않는다.
     */
    @Scheduled(cron = "0 10 * * * *", zone = "Asia/Seoul")
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
