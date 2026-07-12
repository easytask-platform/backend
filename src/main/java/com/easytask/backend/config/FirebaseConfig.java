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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Initializes the Firebase Admin SDK from the service-account key file pointed
 * at by {@code FIREBASE_CREDENTIALS} (env var or property). When unset, push is
 * disabled: the bean resolves to null and consumers must treat it as optional
 * (inject via {@code ObjectProvider}). Dev and test run fine without Firebase.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    FirebaseMessaging firebaseMessaging(
            @Value("${easytask.firebase.credentials:}") String credentialsPath) {
        if (credentialsPath.isBlank()) {
            log.info("FIREBASE_CREDENTIALS not set — push notifications disabled");
            return null;
        }
        try (InputStream credentialsStream = new FileInputStream(credentialsPath)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);
            String projectId = credentials instanceof ServiceAccountCredentials serviceAccount
                    ? serviceAccount.getProjectId() : null;
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(FirebaseOptions.builder()
                            .setCredentials(credentials)
                            .setProjectId(projectId)
                            .build())
                    : FirebaseApp.getInstance();
            log.info("Firebase initialized for project '{}' — push notifications enabled",
                    app.getOptions().getProjectId());
            return FirebaseMessaging.getInstance(app);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to initialize Firebase from FIREBASE_CREDENTIALS=" + credentialsPath, e);
        }
    }
}
