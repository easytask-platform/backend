package com.easytask.backend.notification;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PushDispatcherTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<FirebaseMessaging> provider = mock(ObjectProvider.class);
    private final FirebaseMessaging messaging = mock(FirebaseMessaging.class);
    private final DeviceTokenRepository repository = mock(DeviceTokenRepository.class);
    private final PushDispatcher dispatcher = new PushDispatcher(provider, repository);

    private final NotificationCreatedEvent event = new NotificationCreatedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Task title", "Something happened");

    @BeforeEach
    void configureFirebase() {
        when(provider.getIfAvailable()).thenReturn(messaging);
    }

    private DeviceToken device(String token) {
        return DeviceToken.builder().token(token).platform(DevicePlatform.ANDROID).build();
    }

    private SendResponse success() {
        SendResponse response = mock(SendResponse.class);
        when(response.getException()).thenReturn(null);
        return response;
    }

    private SendResponse failure(MessagingErrorCode code) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(code);
        SendResponse response = mock(SendResponse.class);
        when(response.getException()).thenReturn(exception);
        return response;
    }

    private void stubSend(SendResponse... responses) throws Exception {
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(List.of(responses));
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);
    }

    @Test
    void sendsMulticastToAllRecipientTokens() throws Exception {
        when(repository.findAllByUserId(event.recipientId()))
                .thenReturn(List.of(device("token-a"), device("token-b")));
        stubSend(success(), success());

        dispatcher.onNotificationCreated(event);

        verify(messaging).sendEachForMulticast(any(MulticastMessage.class));
        verify(repository, never()).deleteByToken(any());
    }

    @Test
    void doesNothingWhenRecipientHasNoDevices() throws Exception {
        when(repository.findAllByUserId(event.recipientId())).thenReturn(List.of());

        dispatcher.onNotificationCreated(event);

        verify(messaging, never()).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void doesNothingWhenFirebaseUnconfigured() {
        when(provider.getIfAvailable()).thenReturn(null);

        dispatcher.onNotificationCreated(event);

        verifyNoInteractions(messaging, repository);
    }

    @Test
    void deletesTokensReportedUnregistered() throws Exception {
        when(repository.findAllByUserId(event.recipientId()))
                .thenReturn(List.of(device("stale-token"), device("live-token")));
        stubSend(failure(MessagingErrorCode.UNREGISTERED), success());

        dispatcher.onNotificationCreated(event);

        verify(repository).deleteByToken("stale-token");
        verify(repository, never()).deleteByToken("live-token");
    }

    @Test
    void keepsTokensOnTransientErrors() throws Exception {
        when(repository.findAllByUserId(event.recipientId())).thenReturn(List.of(device("token-a")));
        stubSend(failure(MessagingErrorCode.UNAVAILABLE));

        dispatcher.onNotificationCreated(event);

        verify(repository, never()).deleteByToken(any());
    }
}
