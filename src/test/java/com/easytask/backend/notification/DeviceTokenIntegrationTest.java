package com.easytask.backend.notification;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class DeviceTokenIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private ObjectProvider<FirebaseMessaging> firebaseMessaging;

    private String registerAndLogin() throws Exception {
        String email = uniqueEmail();
        registerOrganization("DeviceOrg" + COUNTER.incrementAndGet(), email);
        return loginToken(email, "password123");
    }

    private void registerDevice(String accessToken, String deviceToken, int expectedStatus) throws Exception {
        exchange(post("/api/v1/me/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "platform": "ANDROID"}
                                """.formatted(deviceToken)),
                expectedStatus);
    }

    @Test
    void registerCreatesTokenAndIsIdempotent() throws Exception {
        String accessToken = registerAndLogin();
        String token = "fcm-" + UUID.randomUUID();

        registerDevice(accessToken, token, 204);
        registerDevice(accessToken, token, 204);

        assertThat(deviceTokenRepository.findByToken(token)).isPresent();
        assertThat(deviceTokenRepository.findAll().stream()
                .filter(dt -> dt.getToken().equals(token)))
                .hasSize(1);
    }

    @Test
    void reRegisteringSameTokenReassignsToNewUser() throws Exception {
        String firstUserToken = registerAndLogin();
        String secondUserToken = registerAndLogin();
        String token = "fcm-" + UUID.randomUUID();

        registerDevice(firstUserToken, token, 204);
        UUID firstOwner = deviceTokenRepository.findByToken(token).orElseThrow().getUser().getId();

        registerDevice(secondUserToken, token, 204);
        UUID secondOwner = deviceTokenRepository.findByToken(token).orElseThrow().getUser().getId();

        assertThat(secondOwner).isNotEqualTo(firstOwner);
    }

    @Test
    void unregisterDeletesOwnTokenOnly() throws Exception {
        String ownerToken = registerAndLogin();
        String otherToken = registerAndLogin();
        String token = "fcm-" + UUID.randomUUID();
        registerDevice(ownerToken, token, 204);

        // someone else deleting the token is a silent no-op
        exchange(delete("/api/v1/me/devices/{token}", token)
                .header("Authorization", "Bearer " + otherToken), 204);
        assertThat(deviceTokenRepository.findByToken(token)).isPresent();

        // the owner deleting it removes it; repeating is idempotent
        exchange(delete("/api/v1/me/devices/{token}", token)
                .header("Authorization", "Bearer " + ownerToken), 204);
        assertThat(deviceTokenRepository.findByToken(token)).isEmpty();
        exchange(delete("/api/v1/me/devices/{token}", token)
                .header("Authorization", "Bearer " + ownerToken), 204);
    }

    @Test
    void validationAndAuthErrors() throws Exception {
        String accessToken = registerAndLogin();

        JsonNode error = exchange(post("/api/v1/me/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "", "platform": "ANDROID"}
                                """),
                400);
        assertThat(error.path("code").asText()).isNotBlank();

        exchange(post("/api/v1/me/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "abc", "platform": "ANDROID"}
                                """),
                401);
    }

    @Test
    void firebaseIsNotConfiguredUnderTestProfile() {
        assertThat(firebaseMessaging.getIfAvailable()).isNull();
    }
}
