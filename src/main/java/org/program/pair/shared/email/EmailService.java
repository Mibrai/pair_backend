package org.program.pair.shared.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    /**
     * Envoyer un email de notification
     * TODO: Implémenter avec Spring Mail ou service externe (SendGrid, Postmark)
     */
    public void sendNotificationEmail(UUID userId, NotificationType type, Map<String, Object> payload) {
        log.info("Email notification {} would be sent to user {} (not implemented)", type, userId);
        // TODO: Implémenter envoi email réel
        // 1. Récupérer l'utilisateur et son email
        // 2. Construire le template email selon le type
        // 3. Envoyer via JavaMailSender ou API externe
    }

    /**
     * Envoyer email de vérification
     */
    public void sendVerificationEmail(String email, String verificationToken) {
        log.info("Verification email would be sent to {} (not implemented)", email);
        // TODO: Implémenter
    }

    /**
     * Envoyer email de reset password
     */
    public void sendPasswordResetEmail(String email, String resetToken) {
        log.info("Password reset email would be sent to {} (not implemented)", email);
        // TODO: Implémenter
    }
}
