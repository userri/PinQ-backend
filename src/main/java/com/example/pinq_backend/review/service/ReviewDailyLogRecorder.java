package com.example.pinq_backend.review.service;

import com.example.pinq_backend.review.domain.ReviewDailyLog;
import com.example.pinq_backend.review.repository.ReviewDailyLogRepository;
import com.example.pinq_backend.user.repository.UserRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일별 복습 기록 upsert — 채점 트랜잭션과 분리된 별도 빈.
 *
 * 왜 별도 빈 + REQUIRES_NEW 인가:
 *  같은 트랜잭션 안에서 save() 의 유니크 제약 위반을 catch 해도 Hibernate 는
 *  세션을 rollback-only 로 마킹하므로, 채점 전체(스테이지 진급·졸업 카운터)가
 *  커밋 시점에 함께 실패한다. 새 트랜잭션으로 격리하면 로그 기록의 실패가
 *  채점 결과에 전염되지 않는다. (NotificationService 의 전송 로그와 동일한 원리.
 *  self-invocation 은 프록시를 타지 않으므로 별도 빈이어야 한다.)
 *
 * 경합 시 정책: 같은 날 첫 복습 두 건이 동시에 로그를 생성하면 한쪽은 제약 위반으로
 * 버려진다(카운트 1 유실). 이 카운트는 잔디 툴팁용 근사치이고 잔디 level 은
 * 존재 여부만 보므로(복습만 한 날 = level 1 고정) 유실이 화면에 영향을 주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewDailyLogRecorder {

    private final ReviewDailyLogRepository reviewDailyLogRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, LocalDate reviewDate, boolean correct) {
        var existing = reviewDailyLogRepository.findByUserIdAndReviewDate(userId, reviewDate);
        if (existing.isPresent()) {
            existing.get().record(correct);
            return;
        }
        try {
            reviewDailyLogRepository.save(ReviewDailyLog.firstReviewOfDay(
                    userRepository.getReferenceById(userId), reviewDate, correct));
        } catch (DataIntegrityViolationException e) {
            // 동시 생성 경합 — 이 트랜잭션은 이미 rollback-only 라 여기서 증가를
            // 시도해도 반영되지 않는다. 근사치 1건 유실을 허용하고 조용히 종료.
            log.debug("일별 복습 로그 동시 생성 경합, 1건 스킵. userId={}, date={}", userId, reviewDate);
        }
    }
}
