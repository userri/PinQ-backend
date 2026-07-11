package com.example.pinq_backend.quiz.repository;

import com.example.pinq_backend.quiz.domain.TrialQuiz;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrialQuizRepository extends JpaRepository<TrialQuiz, Long> {
}
