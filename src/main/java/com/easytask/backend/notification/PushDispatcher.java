package com.easytask.backend.notification;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Sends FCM data messages for freshly committed notifications. Runs after the
 * writing transaction commits (never pushes a rolled-back notification) and on
 * an async thread (never blocks the user's request). No-op when Firebase is
 * unconfigured or the recipient has no registered devices.
 */
@Component
@RequiredArgsConstructor
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final ObjectProvider<FirebaseMessaging> firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        FirebaseMessaging messaging = firebaseMessaging.getIfAvailable();
        if (messaging == null) {
            return;
        }
        List<String> tokens = deviceTokenRepository.findAllByUserId(event.recipientId()).stream()
                .map(DeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            return;
        }
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .putData("notificationId", event.notificationId().toString())
                .putData("taskId", event.taskId().toString())
                .putData("title", event.title())
                .putData("body", event.message())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build())
                .build();
        try {
            BatchResponse response = messaging.sendEachForMulticast(message);
            log.debug("Push for notification {}: {} sent, {} failed",
                    event.notificationId(), response.getSuccessCount(), response.getFailureCount());
            pruneInvalidTokens(tokens, response);
        } catch (FirebaseMessagingException e) {
            log.warn("Push dispatch failed for notification {}", event.notificationId(), e);
        }
    }

    /** Tokens FCM reports as gone (app uninstalled, token rotated) are deleted. */
    private void pruneInvalidTokens(List<String> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            FirebaseMessagingException exception = responses.get(i).getException();
            if (exception == null) {
                continue;
            }
            MessagingErrorCode code = exception.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("Removing invalid device token (FCM says {})", code);
                deviceTokenRepository.deleteByToken(tokens.get(i));
            }
        }
    }
}
