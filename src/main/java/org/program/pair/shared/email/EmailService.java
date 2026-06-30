package org.program.pair.shared.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${email.from:noreply@pair.app}")
    private String fromAddress;

    @Value("${email.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${spring.mail.host:}")
    private String smtpHost;

    public void sendVerificationEmail(String email, String token) {
        if (!isMailConfigured()) {
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
        send(email, "Vérifiez votre adresse Pair", html);
    }

    public void sendPasswordResetEmail(String email, String token) {
        if (!isMailConfigured()) {
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
        send(email, "Réinitialisation de mot de passe Pair", html);
    }

    public void sendNotificationEmail(UUID userId, NotificationType type, Map<String, Object> payload) {
        // Notification emails sont groupées en digest — pas d'envoi direct ici
        log.debug("Notification email queued for digest: type={} userId={}", type, userId);
    }

    private void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            // Ne pas faire échouer l'opération principale si l'email échoue
            log.error("Échec envoi email à {}: {}", to, e.getMessage());
        }
    }

    private boolean isMailConfigured() {
        return smtpHost != null && !smtpHost.isBlank();
    }
}
