package com.easytask.backend.notification;

import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final AppUserRepository appUserRepository;

    /**
     * Upsert: a token belongs to exactly one user. If it already exists it is
     * reassigned to the current user (same physical device, new login) and its
     * last-seen timestamp is refreshed.
     */
    @Transactional
    public void register(UUID userId, RegisterDeviceRequest request) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        DeviceToken deviceToken = deviceTokenRepository.findByToken(request.token())
                .orElseGet(() -> DeviceToken.builder().token(request.token()).build());
        deviceToken.setUser(user);
        deviceToken.setPlatform(request.platform());
        deviceToken.setLastSeenAt(Instant.now());
        deviceTokenRepository.save(deviceToken);
    }

    /** Idempotent: deleting a token that is absent (or not yours) is a no-op. */
    @Transactional
    public void unregister(UUID userId, String token) {
        deviceTokenRepository.deleteByUserIdAndToken(userId, token);
    }
}
