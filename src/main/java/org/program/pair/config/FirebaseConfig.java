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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Client FCM, présent uniquement quand {@code firebase.enabled=true}.
 *
 * <p><b>Deux sources d'identifiants, et pourquoi.</b> Le JSON de compte de
 * service contient une clé privée. En production il ne peut venir ni d'un
 * fichier — le disque du conteneur Railway est reconstruit à chaque
 * déploiement — ni du classpath, qui supposerait de committer le secret dans un
 * dépôt public. Il voyage donc en base64 dans une variable d'environnement.
 * Le chemin de fichier reste la voie du développement local, où déposer un
 * fichier ne pose aucun problème.
 *
 * <ol>
 *   <li>{@code firebase.credentials-base64} ({@code FIREBASE_CREDENTIALS_BASE64})
 *       — production ;</li>
 *   <li>{@code firebase.credentials-path} ({@code FIREBASE_CREDENTIALS_PATH})
 *       — développement local, chemin de fichier ou {@code classpath:} ;</li>
 *   <li>aucune des deux : démarrage refusé.</li>
 * </ol>
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

    @Value("${firebase.credentials-base64:}")
    private String credentialsBase64;

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        CredentialsSource source = resolveCredentials(credentialsBase64, credentialsPath);

        try (InputStream serviceAccount = source.open()) {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options)
                : FirebaseApp.getInstance();
            log.info("Firebase initialized successfully (push notifications enabled, source: {})",
                source.label());

            return FirebaseMessaging.getInstance(app);
        } catch (Exception e) {
            // source.label() vaut « base64 » et non le secret lui-même : ce
            // message part dans les journaux.
            throw new IllegalStateException(
                "Initialisation Firebase impossible (source : " + source.label() + ") : " + e.getMessage()
                    + ". Le JSON de compte de service se télécharge depuis "
                    + "https://console.firebase.google.com/project/_/settings/serviceaccounts/adminsdk",
                e);
        }
    }

    /**
     * D'où lire le JSON de compte de service, base64 d'abord.
     *
     * <p>Statique et sans état pour être vérifiable seule : décider de la source
     * est la partie qui casse en pratique, initialiser Firebase demanderait un
     * vrai secret et un appel réseau.
     *
     * @throws IllegalStateException si aucune source n'est renseignée, ou si le
     *                               base64 l'est mais ne se décode pas
     */
    static CredentialsSource resolveCredentials(String credentialsBase64, String credentialsPath) {
        if (credentialsBase64 != null && !credentialsBase64.isBlank()) {
            return new CredentialsSource("base64", decodeBase64(credentialsBase64));
        }
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            return new CredentialsSource(credentialsPath, () -> openPath(credentialsPath));
        }
        throw new IllegalStateException(
            "firebase.enabled=true mais aucun identifiant n'est renseigné. "
                + "Posez FIREBASE_CREDENTIALS_BASE64 (le JSON de compte de service encodé en base64 — "
                + "la voie de la production, où le disque est éphémère et le dépôt public) "
                + "ou FIREBASE_CREDENTIALS_PATH (chemin du fichier JSON, ou "
                + "classpath:firebase-service-account.json — la voie du développement local). "
                + "Sinon, posez FIREBASE_ENABLED=false pour assumer l'absence de push.");
    }

    /**
     * Décodage du secret.
     *
     * <p>Le {@code trim()} n'est pas une précaution de principe : une variable
     * d'environnement collée depuis un terminal traîne presque toujours un
     * retour à la ligne, et le décodeur échoue alors sur un message qui ne dit
     * rien de la cause.
     *
     * <p>L'erreur nomme la variable, parce qu'un secret mal collé est l'erreur
     * la plus probable — mais ne montre jamais son contenu.
     */
    private static InputStreamSupplier decodeBase64(String credentialsBase64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(credentialsBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "FIREBASE_CREDENTIALS_BASE64 est renseignée mais n'est pas du base64 valide : "
                    + e.getMessage() + ". Réencodez le JSON de compte de service sans retour à la ligne, "
                    + "par exemple : base64 -i service-account.json | tr -d '\\n'",
                e);
        }
        return () -> new ByteArrayInputStream(decoded);
    }

    private static InputStream openPath(String credentialsPath) throws Exception {
        if (credentialsPath.startsWith("classpath:")) {
            Resource resource = new ClassPathResource(credentialsPath.substring("classpath:".length()));
            return resource.getInputStream();
        }
        return Files.newInputStream(Path.of(credentialsPath));
    }

    /**
     * Une source d'identifiants et son étiquette de journal.
     *
     * <p>{@code label} vaut {@code "base64"} ou le chemin du fichier : jamais le
     * secret, puisqu'il est écrit dans les journaux et dans les messages
     * d'erreur.
     */
    record CredentialsSource(String label, InputStreamSupplier supplier) {

        InputStream open() throws Exception {
            return supplier.open();
        }
    }

    /** Ouverture différée : la source est choisie avant d'être lue. */
    @FunctionalInterface
    interface InputStreamSupplier {
        InputStream open() throws Exception;
    }
}
