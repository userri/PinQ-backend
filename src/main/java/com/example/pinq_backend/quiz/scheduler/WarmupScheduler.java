package com.example.pinq_backend.quiz.scheduler;

import com.example.pinq_backend.quiz.service.QuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스케줄 프리워밍 — 아침 첫 사용자의 콜드 지연을 없앤다.
 *
 * 843MB VM 에서는 밤샘 유휴 + 06시 생성 배치의 메모리 스파이크로 앱·MySQL 의
 * 핫 페이지가 스왑으로 축출된다. 그 상태에서 아침 첫 접속자가 page-fault 복구
 * 비용(수 초)을 문다(2026-07-22 진단). 배포 워밍업(deploy.sh)은 배포 시점 1회뿐이라
 * 매일 아침 이 케이스를 못 막으므로, 사용자 도착 직전·배치 직후에 읽기 경로를
 * 미리 돌려 페이지를 RAM 으로 복귀시킨다.
 *
 * 인스턴스를 "깨우는" 것이 아니다 — 상시 구동 VM 이라 JVM 은 이미 24시간 돌고 있고,
 * 이 작업은 RAM 에 상주할 페이지를 콜드→핫으로 교체할 뿐이다.
 * 대기 슬롯(blue/green)은 stop 상태라 라이브 컨테이너 하나에서만 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmupScheduler {

    private final QuizService quizService;

    /** 06:12 KST — 06시 생성 배치(≈06:07 완료)와 06:10 가드 직후, 배치가 밀어낸 페이지 복귀. */
    @Scheduled(cron = "0 12 6 * * *", zone = "Asia/Seoul")
    public void postBatchWarmup() {
        warmup("배치 직후");
    }

    /** 08:00 KST — 아침 사용 피크 직전 프리워밍(06:12 이후 재축출분까지 복귀). */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void morningWarmup() {
        warmup("아침 프리워밍");
    }

    private void warmup(String reason) {
        try {
            int[] warmed = quizService.warmupTodayReadPath();
            log.info("[워밍업] {} 완료 — 퀴즈 {}건 / 선지 {}개 로드", reason, warmed[0], warmed[1]);
        } catch (Exception e) {
            // best-effort: 워밍업 실패가 서비스에 영향을 주면 안 된다.
            log.warn("[워밍업] {} 실패 (무해, 서비스 영향 없음)", reason, e);
        }
    }
}
