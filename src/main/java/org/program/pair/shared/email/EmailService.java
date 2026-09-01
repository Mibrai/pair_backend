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

    /**
     * Racine publique de l'API, sur laquelle sont bâtis les liens envoyés par
     * e-mail.
     *
     * <p>Le défaut {@code localhost:3000} vaut pour le développement. En
     * production il n'avait jamais été surchargé : chaque e-mail de
     * vérification est parti pendant des mois avec un lien vers la machine du
     * destinataire. C'est le ticket du 25 août 2026 ; le profil {@code railway}
     * porte désormais son propre défaut, pour que l'oubli d'une variable
     * d'environnement ne puisse plus produire ce résultat.
     */
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
            log.info("[DEV] Verification link for {}: {}", email, lienVerification(token));
            return;
        }
        String verifyUrl = lienVerification(token);
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

    /**
     * Le lien pointe sur ce serveur, et non sur un chemin de frontend web : il
     * n'existe aucun site qui servirait cette page, et les deux chemins
     * plausibles rendaient 404. La route sait rendre du HTML quand c'est un
     * navigateur qui la demande.
     *
     * <p><b>Le chemin court, et non {@code /api/auth/verify-email?token=…}.</b>
     * C'est lui, et lui seul, que le fichier d'association Apple déclare — un
     * lien vers l'ancien chemin est remis à Safari quoi que fasse l'application,
     * parce qu'iOS ne regarde que l'adresse écrite dans l'e-mail. Le motif dans
     * le fichier d'association et la route {@code /v/{token}} ne servent à rien
     * tant que cette ligne n'a pas changé. L'ancien chemin reste servi pour les
     * liens déjà partis.
     */
    private String lienVerification(String token) {
        return baseUrl + "/v/" + token;
    }

    public void sendPasswordResetEmail(String email, String token) {
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Password reset link for {}: {}/reset-password?token={}", email, baseUrl, token);
            return;
        }
        // NOTE : ce chemin, lui, n'a toujours pas de page. Le rendre utilisable
        // demande un formulaire (le jeton et le nouveau mot de passe partent en
        // POST), pas une simple bascule HTML comme la vérification. Hors du
        // ticket du 25 août, qui ne portait que sur la vérification d'adresse —
        // signalé plutôt que corrigé à moitié.
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

    /**
     * Le gabarit ① : demander à un contact hors meetDo son accord pour être
     * prévenu si son proche ne confirme pas son retour.
     *
     * <p><b>Un seul lien, vers une page, et non deux liens accepter / refuser.</b>
     * La demande décrivait « deux liens » ; nous avons obtenu du chantier mobile
     * qu'ils deviennent une page portant deux boutons. La raison est concrète :
     * les scanners de messagerie et les aperçus de liens suivent automatiquement
     * les {@code GET}. Un lien « refuser » suivi par un robot poserait un refus —
     * définitif et global au numéro — sans que le propriétaire du téléphone ait
     * rien fait. Le lien mène donc à une page ; la décision se prend par un bouton,
     * en {@code POST}, que rien ne pré-charge.
     *
     * <p>La phrase « un seul message vous sera envoyé » n'est pas une politesse :
     * c'est un engagement tenu par le code, qui n'envoie jamais de relance.
     */
    public void sendGuardianConsentEmail(String email, String ownerName, String pageUrl) {
        String qui = (ownerName == null || ownerName.isBlank()) ? "Une personne" : escape(ownerName);
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Guardian consent link for {} (parrain: {}): {}", email, qui, pageUrl);
            return;
        }
        String html = """
            <h2>%s vous a désigné comme contact de confiance</h2>
            <p>Sur meetDo, %s peut « armer une veille » avant une sortie : si cette
               personne ne confirme pas son retour à temps, vous seriez prévenu — et
               vous seul, après plusieurs rappels qui lui sont d'abord adressés.</p>
            <p>Votre accord est demandé avant quoi que ce soit. Ouvrez la page
               ci-dessous pour <strong>accepter</strong> ou <strong>refuser</strong> :</p>
            <a href="%s" style="background:#4F46E5;color:white;padding:12px 24px;border-radius:6px;text-decoration:none;display:inline-block;">
              Voir la demande
            </a>
            <p style="color:#6b757d;font-size:14px;margin-top:20px;">
              Un seul message vous sera envoyé, sans réponse de votre part. Si vous
              refusez, votre numéro ne pourra plus être désigné par personne sur meetDo.
            </p>
            """.formatted(qui, qui, pageUrl);

        boolean sent = resendEmailService.sendHtmlEmail(email,
            qui + " vous a désigné comme contact de confiance — meetDo", html);
        if (!sent) {
            log.error("Failed to send guardian consent email to {}", email);
        }
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
