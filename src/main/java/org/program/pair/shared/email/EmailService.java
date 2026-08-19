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

    /**
     * De l'identifiant à l'adresse. Injecté comme fonction plutôt que par une
     * dépendance au dépôt utilisateur : cette classe vit dans {@code shared} et
     * sert aussi l'authentification, qui n'a rien à voir avec les notifications.
     */
    private final java.util.function.Function<UUID, String> recipientEmail;

    @Value("${email.from:noreply@pair.app}")
    private String fromAddress;

    @Value("${email.base-url:http://localhost:3000}")
    private String baseUrl;

    public EmailService(ResendEmailService resendEmailService,
                        org.program.pair.repository.UserRepository userRepository) {
        this.resendEmailService = resendEmailService;
        this.recipientEmail = userId -> userRepository.findById(userId)
            .map(org.program.pair.domain.user.User::getEmail)
            .orElse(null);
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

    /**
     * L'e-mail d'une notification — pour les seules notifications qui le méritent.
     *
     * <p><b>Ce que cette méthode faisait avant : rien.</b> Elle journalisait en
     * debug, avec un commentaire renvoyant à un digest « géré par des jobs
     * Quartz » qui n'ont jamais été écrits. Cocher « recevoir les e-mails » ne
     * produisait donc aucun e-mail, et personne ne pouvait s'en apercevoir.
     *
     * <p><b>Pourquoi elle reste bornée.</b> L'envoyer pour les trente et un types
     * transformerait chaque notification en e-mail, ce que personne n'a demandé
     * et qui ferait fuir les gens plus sûrement qu'aucune fonctionnalité. Le
     * digest reste à écrire ; il l'était déjà, à ceci près que le code ne le
     * prétend plus.
     *
     * <p><b>Le filtre est {@code warrantsEmail} et non {@code isCritical}</b>,
     * depuis que les heures de silence ont donné un second usage à ce dernier.
     * Les deux questions se ressemblent mais ne se répondent pas ensemble :
     * {@code PROGRAM_REMINDER} doit traverser le silence — sinon un réglage de
     * confort fait manquer une séance à laquelle on s'était engagé — sans pour
     * autant produire un e-mail par séance rejointe. Les avoir laissés confondus
     * aurait rempli les boîtes, et fait couper le canal entier, y compris pour
     * les annulations qui en sont la raison d'être.
     */
    public void sendNotificationEmail(UUID userId, NotificationType type, Map<String, Object> payload) {
        if (!type.warrantsEmail()) {
            log.debug("Notification non critique, pas d'e-mail : type={} userId={}", type, userId);
            return;
        }

        String email = recipientEmail.apply(userId);
        if (email == null || email.isBlank()) {
            return;
        }

        String subject = String.valueOf(payload.getOrDefault("programTitle", "Votre créneau meetDo"));
        String text = notificationText(type, payload);

        if (!resendEmailService.isEnabled()) {
            // Même repli que la vérification d'adresse : en développement, le
            // contenu part dans les journaux plutôt que nulle part.
            log.info("[DEV] E-mail {} pour {} : {}", type, email, text);
            return;
        }

        boolean sent = resendEmailService.sendEmail(email, subjectFor(type, subject), text,
            htmlFor(type, subject, text));
        if (!sent) {
            // Un e-mail perdu ne doit pas emporter l'annulation elle-même : le
            // push et la notification in-app sont déjà partis.
            log.error("Échec de l'e-mail {} vers {}", type, email);
        }
    }

    private String subjectFor(NotificationType type, String programTitle) {
        return type == NotificationType.SLOT_CANCELLED
            ? "Séance annulée : " + programTitle
            : "meetDo — " + programTitle;
    }

    private String notificationText(NotificationType type, Map<String, Object> payload) {
        StringBuilder text = new StringBuilder();
        text.append("La séance « ")
            .append(payload.getOrDefault("programTitle", "votre créneau"))
            .append(" » est annulée.");

        Object reason = payload.get("cancellationReason");
        if (reason != null && !String.valueOf(reason).isBlank()) {
            text.append("\n\nMotif indiqué par l'organisateur : ").append(reason);
        }

        Object alternatives = payload.get("alternativesCount");
        if (alternatives instanceof Number count && count.intValue() > 0) {
            text.append("\n\n").append(count.intValue())
                .append(count.intValue() > 1
                    ? " autres créneaux de la même activité ont lieu près de chez vous."
                    : " autre créneau de la même activité a lieu près de chez vous.");
        }

        return text.toString();
    }

    /**
     * Version HTML du même texte. Les valeurs sont échappées : elles viennent de
     * l'organisateur — titre du programme, motif d'annulation — et le HTML est
     * assemblé par concaténation, ce qui n'échappe rien tout seul.
     */
    private String htmlFor(NotificationType type, String programTitle, String text) {
        return """
            <div style="font-family:system-ui,sans-serif;line-height:1.5;">
              <h2 style="font-size:1.1rem;">%s</h2>
              <p style="white-space:pre-line;">%s</p>
            </div>
            """.formatted(escape(subjectFor(type, programTitle)), escape(text));
    }

    private static String escape(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
