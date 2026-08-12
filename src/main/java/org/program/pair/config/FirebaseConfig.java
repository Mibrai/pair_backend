package org.program.pair.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client FCM, présent uniquement quand {@code firebase.enabled=true}.
 *
 * <p><b>Pourquoi l'échec est bruyant.</b> Cette fabrique renvoyait {@code null}
 * dès que quoi que ce soit manquait — variable absente, fichier introuvable, clé
 * illisible — et l'application démarrait normalement. Le seul témoin était une
 * ligne {@code WARN} dans le journal de démarrage, que personne ne relit : en
 * production, la conséquence était un badge d'icône figé et des notifications
 * jamais reçues, sans la moindre erreur pour le dire.
 *
 * <p>Deux régimes désormais, et rien entre les deux :
 *
 * <ul>
 *   <li>{@code firebase.enabled=false} (défaut) — aucun bean ici, aucun envoi,
 *       et c'est {@code NoOpPushNotificationService} qui est câblé. Assumé.</li>
 *   <li>{@code firebase.enabled=true} — un identifiant manquant ou invalide
 *       <b>empêche le démarrage</b>. Une configuration push cassée se voit au
 *       déploiement, pas des semaines plus tard sur un téléphone.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.credentials-path:}")
    private String credentialsPath;

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            throw new IllegalStateException(
                "firebase.enabled=true mais firebase.credentials-path est vide. "
                    + "Renseignez FIREBASE_CREDENTIALS_PATH (chemin du JSON de compte de service, "
                    + "ou classpath:firebase-service-account.json), ou posez FIREBASE_ENABLED=false "
                    + "pour assumer l'absence de push.");
        }

        try (InputStream serviceAccount = openCredentials()) {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options)
                : FirebaseApp.getInstance();
            log.info("Firebase initialized successfully (push notifications enabled)");

            return FirebaseMessaging.getInstance(app);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Initialisation Firebase impossible depuis " + credentialsPath + " : " + e.getMessage()
                    + ". Le JSON de compte de service se télécharge depuis "
                    + "https://console.firebase.google.com/project/_/settings/serviceaccounts/adminsdk",
                e);
        }
    }

    private InputStream openCredentials() throws Exception {
        if (credentialsPath.startsWith("classpath:")) {
            Resource resource = new ClassPathResource(credentialsPath.substring("classpath:".length()));
            return resource.getInputStream();
        }
        return Files.newInputStream(Path.of(credentialsPath));
    }
}
