package org.program.pair.shared.email;

import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.email.GabaritEmail;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.user.User;
import org.program.pair.shared.i18n.Messages;
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

    private final OutboxService outbox;
    private final Messages messages;

    /**
     * L'enveloppe de marque.
     *
     * <p>Injectée ici alors que {@link ResendEmailService} l'applique déjà à
     * tout ce qui sort : c'est l'<b>accent</b> qu'on vient chercher. Le filet de
     * la porte de sortie habille en violet, ce qui convient à un courrier de
     * compte ; une annulation de séance et une demande de contact de confiance
     * ne se lisent pas dans la même couleur, et ce sont eux qui le savent.
     */
    private final GabaritEmail gabarit;

    public EmailService(ResendEmailService resendEmailService,
                        org.program.pair.repository.UserRepository userRepository,
                        OutboxService outbox,
                        Messages messages,
                        GabaritEmail gabarit) {
        this.resendEmailService = resendEmailService;
        this.outbox = outbox;
        this.messages = messages;
        this.gabarit = gabarit;
        this.recipientEmail = userId -> userRepository.findById(userId)
            .map(User::getEmail)
            .orElse(null);
    }

    /**
     * L'e-mail de vérification d'une adresse — déposé dans l'outbox, pas envoyé
     * d'ici.
     *
     * <p><b>Pourquoi l'outbox, depuis le 07/09.</b> Cet e-mail partait par un
     * appel HTTP direct, bloquant, <b>à l'intérieur de la transaction
     * d'inscription</b>, et son identifiant Resend était jeté par
     * {@code sendHtmlEmail}. Trois conséquences, dont une seule se voyait :
     * l'inscription attendait un aller-retour vers le fournisseur ; un
     * redéploiement au mauvais moment perdait l'envoi ; et surtout l'accusé de
     * remise — écrit le 1er septembre pour les alertes — ne pouvait rapporter
     * aucun rebond, faute de ligne à recouper. C'était le seul de nos courriers
     * dans ce cas.
     *
     * <p><b>Le corps est composé ici, et non au balayage</b>, parce que la langue
     * se lit sur le fil de la requête : au moment où l'outbox enverra, le
     * {@code LocaleContextHolder} sera vide et l'{@code Accept-Language} de
     * l'appareil aura disparu. C'est la même raison qui fait que le sujet est
     * résolu maintenant.
     *
     * <p>Le repli de développement est conservé : sans fournisseur configuré, le
     * lien part dans les journaux et rien n'est déposé — une file qui
     * s'accumulerait pour être refusée cinq fois ne rendrait service à personne.
     */
    public void sendVerificationEmail(User user, String token) {
        String verifyUrl = lienVerification(token);
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Verification link for {}: {}", user.getEmail(), verifyUrl);
            return;
        }
        outbox.enqueueVerificationEmail(user, user.getEmail(),
            messages.get("email.verification.subject"),
            corpsVerification(verifyUrl));
    }

    /**
     * Le lien qui confirme une <b>nouvelle</b> adresse, envoyé à cette
     * nouvelle adresse et à elle seule.
     *
     * <p>C'est le seul endroit où le destinataire n'est pas l'adresse du compte,
     * et c'est la raison d'être de la route : celle qui figure encore sur le
     * compte est précisément celle qui ne reçoit pas. Le message est aussi la
     * preuve demandée — une adresse qui ne peut pas recevoir ce lien ne
     * remplacera jamais l'ancienne.
     */
    public void sendEmailChangeEmail(User user, String nouvelleAdresse, String token) {
        String verifyUrl = lienVerification(token);
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Email change link for {}: {}", nouvelleAdresse, verifyUrl);
            return;
        }
        outbox.enqueueVerificationEmail(user, nouvelleAdresse,
            messages.get("email.change.subject"),
            corpsChangement(verifyUrl, nouvelleAdresse));
    }

    private String corpsChangement(String verifyUrl, String nouvelleAdresse) {
        // Fragment nu : c'est ResendEmailService qui pose l'enveloppe de marque
        // au moment de l'envoi, en violet — l'accent des courriers de compte.
        // Voir GabaritEmail.
        return GabaritEmail.titre(escape(messages.get("email.change.title")))
            + "<p>" + escape(messages.get("email.change.intro", nouvelleAdresse)) + "</p>"
            + GabaritEmail.bouton(verifyUrl, escape(messages.get("email.change.button")))
            + GabaritEmail.note(escape(messages.get("email.change.expiry")));
    }

    /**
     * Le corps de l'e-mail de vérification, dans la langue demandée.
     *
     * <p>Il était un littéral français en dur — ni {@code Accept-Language}, ni
     * préférence de compte. Un utilisateur allemand, dont l'écran d'inscription
     * s'appelle pourtant <i>Registrieren</i>, recevait du français : de quoi
     * classer le message en indésirable sans le lire, et ne jamais signaler
     * qu'on l'avait reçu. La machinerie existait et servait partout ailleurs.
     */
    private String corpsVerification(String verifyUrl) {
        return GabaritEmail.titre(escape(messages.get("email.verification.title")))
            + "<p>" + escape(messages.get("email.verification.intro")) + "</p>"
            + GabaritEmail.bouton(verifyUrl, escape(messages.get("email.verification.button")))
            + GabaritEmail.note(escape(messages.get("email.verification.expiry")));
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
        String html = GabaritEmail.titre("Réinitialisation de votre mot de passe")
            + "<p>Cliquez sur le bouton ci-dessous pour définir un nouveau mot de passe.</p>"
            + GabaritEmail.bouton(resetUrl, "Réinitialiser mon mot de passe")
            + GabaritEmail.note("Ce lien expire dans 30 minutes. Si vous n'avez pas fait "
                + "cette demande, ignorez ce message : votre mot de passe reste inchangé.");

        // L'objet disait « Pair » — le nom du paquet, jamais celui du produit.
        // Un courrier de réinitialisation dont l'objet nomme une marque
        // inconnue est un courrier qu'on signale comme indésirable.
        boolean sent = resendEmailService.sendHtmlEmail(email,
            "Réinitialisation de votre mot de passe — meetDo", html);
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

        // Enveloppe posée ici, en corail : une annulation n'est pas un courrier
        // de compte. Le filet de la porte de sortie la laissera passer telle
        // quelle — envelopper est idempotent.
        boolean sent = resendEmailService.sendEmail(email, subjectFor(type, subject), text,
            gabarit.envelopper(htmlFor(type, subject, text), GabaritEmail.Accent.CORAL));
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
        return GabaritEmail.titre(escape(subjectFor(type, programTitle)))
            + "<p style=\"white-space:pre-line;\">" + escape(text) + "</p>";
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
        // Menthe, comme la page qu'il ouvre et comme « Prévenir un proche » dans
        // l'application : la couleur des gestes de sécurité, d'un bout à l'autre.
        String html = gabarit.envelopper(
            GabaritEmail.titre(qui + " vous a désigné comme contact de confiance")
                + "<p>Sur meetDo, " + qui + " peut « armer une veille » avant une sortie :"
                + " si cette personne ne confirme pas son retour à temps, vous seriez"
                + " prévenu — et vous seul, après plusieurs rappels qui lui sont d'abord"
                + " adressés.</p>"
                + "<p>Votre accord est demandé avant quoi que ce soit.</p>"
                + GabaritEmail.bouton(pageUrl, "Voir la demande", GabaritEmail.Accent.MINT)
                + GabaritEmail.note("Un seul message vous sera envoyé, sans réponse de votre"
                    + " part. Si vous refusez, votre numéro ne pourra plus être désigné par"
                    + " personne sur meetDo."),
            GabaritEmail.Accent.MINT);

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
