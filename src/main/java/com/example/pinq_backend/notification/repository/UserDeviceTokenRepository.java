package com.example.pinq_backend.notification.repository;

import com.example.pinq_backend.notification.domain.UserDeviceToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    Optional<UserDeviceToken> findByToken(String token);

    List<UserDeviceToken> findAllByUserId(Long userId);

    void deleteByToken(String token);
}
