package com.easytask.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Initializes the Firebase Admin SDK from {@code FIREBASE_CREDENTIALS} (env var
 * or property), which may hold either the service-account JSON <b>inline</b>
 * (starts with '{{') or a <b>path</b> to the key file. Inline suits hosts with
 * an ephemeral filesystem (e.g. Render), where a file path isn't practical.
 * When unset, push is disabled: the bean resolves to null and consumers must
 * treat it as optional (inject via {@code ObjectProvider}). Dev and test run
 * fine without Firebase.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    FirebaseMessaging firebaseMessaging(
            @Value("${easytask.firebase.credentials:}") String credentials) {
        if (credentials.isBlank()) {
            log.info("FIREBASE_CREDENTIALS not set — push notifications disabled");
            return null;
        }
        try (InputStream credentialsStream = openCredentials(credentials)) {
            GoogleCredentials googleCredentials = GoogleCredentials.fromStream(credentialsStream);
            String projectId = googleCredentials instanceof ServiceAccountCredentials serviceAccount
                    ? serviceAccount.getProjectId() : null;
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(FirebaseOptions.builder()
                            .setCredentials(googleCredentials)
                            .setProjectId(projectId)
                            .build())
                    : FirebaseApp.getInstance();
            log.info("Firebase initialized for project '{}' — push notifications enabled",
                    app.getOptions().getProjectId());
            return FirebaseMessaging.getInstance(app);
        } catch (IOException e) {
            // Never log the value itself — it may be the raw service-account JSON.
            throw new IllegalStateException("Failed to initialize Firebase from FIREBASE_CREDENTIALS", e);
        }
    }

    /** Inline service-account JSON (starts with '{{') or a path to the key file. */
    private static InputStream openCredentials(String value) throws IOException {
        String trimmed = value.strip();
        if (trimmed.startsWith("{")) {
            return new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8));
        }
        return new FileInputStream(trimmed);
    }
}
