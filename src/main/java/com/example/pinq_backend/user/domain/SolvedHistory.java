package com.example.pinq_backend.user.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 일별 퀴즈 풀이 기록.
 *
 * (user_id, solved_date) 쌍이 유니크 — 하루에 한 행.
 * checkAnswer 호출마다 solved_count / correct_count 를 증분한다.
 *
 * 용도:
 *  - totalSolved : 전체 행의 solved_count 합산
 *  - correctRate : 전체 correct_count / solved_count
 *  - activityGrid: 최근 56일 날짜별 solved_count > 0 여부
 */
@Entity
@Table(
    name = "solved_history",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_solved_history_user_date",
        columnNames = {"user_id", "solved_date"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SolvedHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "solved_date", nullable = false)
    private LocalDate solvedDate;

    @Column(name = "solved_count", nullable = false)
    private int solvedCount;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    public static SolvedHistory create(User user, LocalDate date) {
        SolvedHistory h = new SolvedHistory();
        h.user = user;
        h.solvedDate = date;
        h.solvedCount = 0;
        h.correctCount = 0;
        return h;
    }

    /** checkAnswer 한 번 호출될 때마다 증분. */
    public void record(boolean isCorrect) {
        this.solvedCount++;
        if (isCorrect) {
            this.correctCount++;
        }
    }
}
