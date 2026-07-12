package com.easytask.backend.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findAllByUserId(UUID userId);

    void deleteByUserIdAndToken(UUID userId, String token);

    // called from PushDispatcher's async thread, which has no surrounding transaction
    @Transactional
    void deleteByToken(String token);
}
