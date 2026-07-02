package org.program.pair.shared.email;

import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EmailService {

    private final ResendEmailService resendEmailService;

    @Value("${email.from:noreply@pair.app}")
    private String fromAddress;

    @Value("${email.base-url:http://localhost:3000}")
    private String baseUrl;

    public EmailService(ResendEmailService resendEmailService) {
        this.resendEmailService = resendEmailService;
    }

    public void sendVerificationEmail(String email, String token) {
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Verification link for {}: {}/verify-email?token={}", email, baseUrl, token);
            return;
        }
        String verifyUrl = baseUrl + "/verify-email?token=" + token;
        String html = """
            <h2>Vérifiez votre adresse email</h2>
            <p>Cliquez sur le lien suivant pour activer votre compte Pair :</p>
            <a href="%s" style="background:#4F46E5;color:white;padding:12px 24px;border-radius:6px;text-decoration:none;display:inline-block;">
              Vérifier mon email
            </a>
            <p>Ce lien expire dans 24 heures.</p>
            """.formatted(verifyUrl);

        boolean sent = resendEmailService.sendHtmlEmail(email, "Vérifiez votre adresse Pair", html);
        if (!sent) {
            log.error("Failed to send verification email to {}", email);
        }
    }

    public void sendPasswordResetEmail(String email, String token) {
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Password reset link for {}: {}/reset-password?token={}", email, baseUrl, token);
            return;
        }
        String resetUrl = baseUrl + "/reset-password?token=" + token;
        String html = """
            <h2>Réinitialisation de votre mot de passe</h2>
            <p>Cliquez sur le lien suivant pour définir un nouveau mot de passe :</p>
            <a href="%s" style="background:#4F46E5;color:white;padding:12px 24px;border-radius:6px;text-decoration:none;display:inline-block;">
              Réinitialiser mon mot de passe
            </a>
            <p>Ce lien expire dans 30 minutes. Si vous n'avez pas fait cette demande, ignorez cet email.</p>
            """.formatted(resetUrl);

        boolean sent = resendEmailService.sendHtmlEmail(email, "Réinitialisation de mot de passe Pair", html);
        if (!sent) {
            log.error("Failed to send password reset email to {}", email);
        }
    }

    public void sendNotificationEmail(UUID userId, NotificationType type, Map<String, Object> payload) {
        // Notification emails sont groupées en digest — pas d'envoi direct ici
        log.debug("Notification email queued for digest: type={} userId={}", type, userId);
    }
}
