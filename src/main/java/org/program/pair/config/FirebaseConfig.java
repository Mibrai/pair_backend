package org.program.pair.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.credentials-path:}")
    private String credentialsPath;

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        if (!firebaseEnabled) {
            log.warn("Firebase is disabled. Push notifications will not work.");
            return null;
        }

        if (credentialsPath == null || credentialsPath.isEmpty()) {
            log.warn("Firebase credentials path not configured. Push notifications will not work.");
            return null;
        }

        try {
            InputStream serviceAccount;

            // Try classpath first
            if (credentialsPath.startsWith("classpath:")) {
                String path = credentialsPath.substring("classpath:".length());
                Resource resource = new ClassPathResource(path);
                serviceAccount = resource.getInputStream();
            } else {
                // Try file system
                serviceAccount = new FileInputStream(credentialsPath);
            }

            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

            FirebaseApp app;
            if (FirebaseApp.getApps().isEmpty()) {
                app = FirebaseApp.initializeApp(options);
                log.info("Firebase initialized successfully");
            } else {
                app = FirebaseApp.getInstance();
                log.info("Firebase already initialized");
            }

            return FirebaseMessaging.getInstance(app);

        } catch (Exception e) {
            log.error("Failed to initialize Firebase: {}", e.getMessage());
            log.warn("Push notifications will not work. To enable:");
            log.warn("1. Set firebase.enabled=true in application.properties");
            log.warn("2. Set firebase.credentials-path to your service account JSON");
            log.warn("3. Download from: https://console.firebase.google.com/project/_/settings/serviceaccounts/adminsdk");
            return null;
        }
    }
}
