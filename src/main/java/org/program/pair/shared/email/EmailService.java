package org.program.pair.shared.email;

import org.program.pair.shared.logging.Masque;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.config.Profils;
import org.program.pair.domain.email.GabaritEmail;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.user.User;
import org.program.pair.shared.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EmailService {

    /**
     * Le refus de démarrage, écrit pour être lu une seule fois et dans
     * l'urgence : il nomme le profil, l'état des deux réglages, et le geste.
     */
    private static final String MESSAGE_SANS_FOURNISSEUR = """
        L'envoi d'e-mails n'est pas configuré sous un profil de production (%s) : \
        resend.enabled=%s, resend.api-key %s.

        Le serveur refuse de démarrer plutôt que de se rabattre sur son repli de \
        développement, qui écrit les liens de vérification et de réinitialisation \
        de mot de passe dans les journaux — et un lien de réinitialisation lu dans \
        un journal donne le compte.

        Posez RESEND_ENABLED=true et RESEND_API_KEY.""";

    private final ResendEmailService resendEmailService;

    /**
     * De l'identifiant à l'adresse. Injecté comme fonction plutôt que par une
     * dépendance au dépôt utilisateur : cette classe vit dans {@code shared} et
     * sert aussi l'authentification, qui n'a rien à voir avec les notifications.
     */
    private final java.util.function.Function<UUID, String> recipientEmail;

    /** La langue dans laquelle écrire à une personne. Voir le constructeur. */
    private final java.util.function.Function<UUID, java.util.Locale> recipientLocale;


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

    /**
     * Le seul drapeau qui autorise un lien d'e-mail à passer par les journaux.
     *
     * <p><b>Ce qu'il ferme (P-BS-09).</b> Sans fournisseur configuré, les cinq
     * replis de cette classe écrivaient le lien entier en {@code log.info} —
     * jeton compris pour la réinitialisation de mot de passe. Un journal de
     * plateforme se lit sans les droits de la base et se conserve : quiconque y
     * a accès prenait le compte, sans mot de passe et sans laisser de trace dans
     * l'application. Le repli reste utile là où il n'y a pas de boîte aux
     * lettres, et nulle part ailleurs.
     *
     * <p>Il vaut {@code true} dans {@code application-dev.properties} et
     * {@code false} dans {@code application.properties}, donc partout ailleurs
     * par héritage ; {@code ObservabiliteConfigurationTest} échoue si un profil
     * de déploiement le rallume. Le défaut écrit ici est {@code false} lui aussi :
     * un drapeau de journalisation absent doit se lire « ne journalise pas ».
     */
    @Value("${pair.email.journaliser-liens:false}")
    private boolean journaliserLiens;

    /**
     * Les profils réellement actifs, pour le seul refus de démarrage de
     * {@link #exigerUnFournisseurEnProduction()}.
     *
     * <p>{@code Environment} et non {@code spring.profiles.active} : voir
     * {@link Profils} — un profil activé par variable d'environnement ou par
     * {@code include} n'apparaît pas dans cette propriété.
     */
    private final Environment environment;

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
                        GabaritEmail gabarit,
                        Environment environment,
                        org.program.pair.repository.DeviceTokenRepository deviceTokenRepository) {
        this.resendEmailService = resendEmailService;
        this.outbox = outbox;
        this.messages = messages;
        this.gabarit = gabarit;
        this.environment = environment;
        this.recipientEmail = userId -> userRepository.findById(userId)
            .map(User::getEmail)
            .orElse(null);
        // La langue du destinataire (P-BL-20) : celle de son appareil le plus
        // récemment utilisé, comme les push ; le français à défaut. Un envoi
        // asynchrone n'a ni requête ni Accept-Language à lire.
        this.recipientLocale = userId -> deviceTokenRepository.findByUserId(userId).stream()
            .filter(device -> device.getLocale() != null && !device.getLocale().isBlank())
            .max(java.util.Comparator.comparing(org.program.pair.domain.notification.DeviceToken::getLastUsedAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
            .map(device -> org.program.pair.config.LocaleConfig.closestSupported(device.getLocale()))
            .orElse(org.program.pair.config.LocaleConfig.FRENCH);
    }

    /**
     * En production, pas de fournisseur d'e-mail signifie pas de démarrage.
     *
     * <p><b>Pourquoi un refus, et non un avertissement.</b> Le repli sans
     * fournisseur n'est pas « ne rien envoyer » : c'est <b>envoyer dans les
     * journaux</b>. Une variable d'environnement oubliée ne dégradait donc pas le
     * service, elle déplaçait les liens de vérification et de réinitialisation
     * vers un endroit lisible par quiconque a accès aux journaux de la
     * plateforme — et où ils restent. Le drapeau
     * {@code pair.email.journaliser-liens} empêche l'écriture ; ce contrôle
     * empêche l'état qui la rendait tentante, et surtout il empêche que plus
     * aucun e-mail ne parte sans que personne s'en aperçoive : aujourd'hui,
     * Resend éteint sous {@code railway} ne produit aucune erreur, aucune ligne
     * rouge, et aucun e-mail.
     *
     * <p><b>{@link Profils#PRODUCTION} et non {@link Profils#DEPLOIEMENT}</b> — la
     * différence avec {@code JwtTokenProvider} est voulue. Une clé de signature
     * absente est contournable partout, staging compris ; un fournisseur d'e-mail
     * absent n'expose que là où de vraies adresses reçoivent de vrais liens.
     * Staging n'a pas nécessairement de domaine vérifié chez Resend, et l'y
     * exiger interdirait de monter un environnement d'essai pour fermer un risque
     * qui n'y existe pas.
     *
     * <p>La clé est lue par l'{@code Environment} plutôt que par un second
     * {@code @Value} : ce contrôle est le seul endroit du code qui s'y intéresse,
     * et {@link ResendEmailService} ne l'expose pas. {@code resend.enabled=true}
     * sans clé est le pire des trois états — l'application croit envoyer, et
     * chaque appel échoue côté fournisseur.
     */
    @PostConstruct
    void exigerUnFournisseurEnProduction() {
        if (!Profils.actif(environment, Profils.PRODUCTION)) {
            return;
        }

        String cle = environment.getProperty("resend.api-key", "");
        boolean cleAbsente = cle == null || cle.isBlank();
        if (resendEmailService.isEnabled() && !cleAbsente) {
            return;
        }

        throw new IllegalStateException(MESSAGE_SANS_FOURNISSEUR.formatted(
            Profils.premierProfilActif(environment, Profils.PRODUCTION),
            resendEmailService.isEnabled(),
            cleAbsente ? "absente" : "présente"));
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
            nonEnvoye("vérification d'adresse", "lien " + verifyUrl + " pour " + user.getEmail());
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
            nonEnvoye("changement d'adresse", "lien " + verifyUrl + " pour " + nouvelleAdresse);
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
        // Construite avant le repli, et non après : c'est le lien qui porte le
        // jeton, donc le seul détail que le repli de développement ait à écrire.
        //
        // Le chemin court /r/{token}, pour la même raison que /v/{token} : c'est
        // lui que déclare le fichier d'association Apple. Il a une page depuis le
        // 14/09 (ReinitialisationLinkController) ; l'ancien /reset-password?token=
        // n'en avait aucune et rendait 401. Il reste servi pour les liens déjà partis.
        String resetUrl = baseUrl + "/r/" + token;
        if (!resendEmailService.isEnabled()) {
            nonEnvoye("réinitialisation de mot de passe", "lien " + resetUrl + " pour " + email);
            return;
        }
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
            log.error("Failed to send password reset email to {}", Masque.email(email));
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

        java.util.Locale langue = recipientLocale.apply(userId);
        String subject = String.valueOf(payload.getOrDefault("programTitle",
            texte(langue, "email.notification.defaultSubject", "Votre créneau meetDo")));
        String text = notificationText(langue, type, payload);

        if (!resendEmailService.isEnabled()) {
            // Même repli que la vérification d'adresse : en développement, le
            // contenu part dans les journaux plutôt que nulle part.
            nonEnvoye("notification " + type, "texte « " + text + " » pour " + email);
            return;
        }

        // Enveloppe posée ici, en corail : une annulation n'est pas un courrier
        // de compte. Le filet de la porte de sortie la laissera passer telle
        // quelle — envelopper est idempotent.
        boolean sent = resendEmailService.sendEmail(email, subjectFor(langue, type, subject), text,
            gabarit.envelopper(htmlFor(langue, type, subject, text), GabaritEmail.Accent.CORAL));
        if (!sent) {
            // Un e-mail perdu ne doit pas emporter l'annulation elle-même : le
            // push et la notification in-app sont déjà partis.
            log.error("Échec de l'e-mail {} vers {} (utilisateur {})", type, Masque.email(email), userId);
        }
    }

    /**
     * L'objet, selon le type — et non selon « est-ce une annulation ? ».
     *
     * <p>Un {@code switch} plutôt qu'un ternaire, parce qu'il y a maintenant trois
     * types à l'e-mail et que le ternaire donnait à tous ceux qui ne sont pas
     * {@code SLOT_CANCELLED} un objet qui ne dit rien — « meetDo — Yoga du soir ».
     * Un objet qui ne dit rien est un objet qu'on n'ouvre pas, et un e-mail
     * qu'on n'ouvre pas est un déplacement pour rien.
     */
    String subjectFor(NotificationType type, String programTitle) {
        return subjectFor(org.program.pair.config.LocaleConfig.FRENCH, type, programTitle);
    }

    String subjectFor(java.util.Locale langue, NotificationType type, String programTitle) {
        return switch (type) {
            case SLOT_CANCELLED -> texte(langue, "email.SLOT_CANCELLED.subject",
                "Séance annulée : " + programTitle, programTitle);
            case PROGRAM_CANCELLED -> texte(langue, "email.PROGRAM_CANCELLED.subject",
                "Programme annulé : " + programTitle, programTitle);
            case SCHEDULE_CHANGED -> texte(langue, "email.SCHEDULE_CHANGED.subject",
                "Séance modifiée : " + programTitle, programTitle);
            case SERIES_ENDED -> texte(langue, "email.SERIES_ENDED.subject",
                "La série s'arrête : " + programTitle, programTitle);
            default -> "meetDo — " + programTitle;
        };
    }

    /**
     * Le corps, selon le type.
     *
     * <p><b>Le défaut fermé ici, et il aurait été grave.</b> Cette méthode
     * écrivait « La séance « X » est annulée. » <b>quel que soit le type</b>, sans
     * un {@code if}. Tant que {@code SCHEDULE_CHANGED} n'avait aucun producteur,
     * personne ne pouvait s'en apercevoir ; le jour où il en a eu un (P-BL-06),
     * avancer une séance d'une heure aurait envoyé à chaque inscrit un courriel
     * annonçant son annulation. Le pire message possible : celui qui fait rester
     * chez soi quelqu'un dont la séance a bien lieu.
     *
     * <p><b>Trois types à l'e-mail, trois textes.</b> {@code warrantsEmail} en
     * nomme exactement trois, et le {@code default} ne sert donc qu'à satisfaire
     * le compilateur : {@code sendNotificationEmail} a déjà écarté tout le reste.
     * {@code EmailServiceTest} échoue si un type à l'e-mail n'a pas son texte
     * propre.
     *
     * <p><b>Aucune date n'est écrite ici.</b> Le nouvel horaire demanderait un
     * fuseau et une langue, dont ce point de sortie asynchrone ne dispose pas —
     * ni {@code LocaleContextHolder}, ni l'appareil. L'e-mail dit ce qui a changé
     * et renvoie à la fiche, qui porte l'heure exacte ; la phrase complète
     * « avancée à 18 h, au lieu de 19 h » est composée par le client, qui a les
     * deux. La langue, elle, est celle du destinataire depuis P-BL-20.
     */
    String notificationText(NotificationType type, Map<String, Object> payload) {
        return notificationText(org.program.pair.config.LocaleConfig.FRENCH, type, payload);
    }

    String notificationText(java.util.Locale langue, NotificationType type, Map<String, Object> payload) {
        Object titre = payload.getOrDefault("programTitle",
            texte(langue, "email.notification.defaultTitle", "votre créneau"));
        return switch (type) {
            case SCHEDULE_CHANGED -> scheduleChangedText(langue, payload, titre);
            // Sans ce cas, la fin d'une série tombait sur le texte d'annulation.
            case SERIES_ENDED -> texte(langue, "email.SERIES_ENDED.body",
                "La séance « " + titre + " » ne se répète plus : la prochaine a bien lieu, les"
                    + " suivantes non. Retrouvez les détails dans l'application.", titre);
            case PROGRAM_CANCELLED -> texte(langue, "email.PROGRAM_CANCELLED.body",
                "Le programme « " + titre + " » est annulé.", titre);
            default -> cancellationText(langue, payload, titre);
        };
    }

    /**
     * Une modification, et surtout <b>pas</b> une annulation.
     *
     * <p>Trois phrases distinctes selon ce qui a bougé : l'heure, le lieu, ou les
     * deux. Un texte unique « la séance a été modifiée » obligerait à ouvrir
     * l'application pour savoir s'il faut se réorganiser, ce que la moitié des
     * gens ne fera pas.
     *
     * <p>{@code changedFields} est lue par son écriture textuelle plutôt que
     * déstructurée : elle arrive ici sous forme de liste en mémoire par le chemin
     * courant, mais la même charge utile fait aussi l'aller-retour par une colonne
     * {@code jsonb}, et deux façons de la lire divergeraient. Ce qui est demandé
     * est une présence, pas un ordre.
     */
    private String scheduleChangedText(java.util.Locale langue, Map<String, Object> payload, Object titre) {
        String champs = String.valueOf(payload.getOrDefault("changedFields", ""));
        boolean heure = champs.contains("TIME");
        boolean lieu = champs.contains("PLACE");

        if (heure && lieu) {
            return texte(langue, "email.SCHEDULE_CHANGED.body.both",
                "L'horaire et le lieu de la séance « " + titre + " » ont changé."
                    + " Retrouvez les nouveaux détails dans l'application.", titre);
        }
        if (lieu) {
            return texte(langue, "email.SCHEDULE_CHANGED.body.place",
                "Le lieu de la séance « " + titre + " » a changé."
                    + " Retrouvez le nouveau lieu dans l'application.", titre);
        }
        return texte(langue, "email.SCHEDULE_CHANGED.body.time",
            "L'horaire de la séance « " + titre + " » a changé."
                + " Retrouvez le nouvel horaire dans l'application.", titre);
    }

    /** Le texte d'annulation, inchangé — motif et repli compris. */
    private String cancellationText(java.util.Locale langue, Map<String, Object> payload, Object titre) {
        StringBuilder text = new StringBuilder();
        text.append(texte(langue, "email.SLOT_CANCELLED.body",
            "La séance « " + titre + " » est annulée.", titre));

        Object reason = payload.get("cancellationReason");
        if (reason != null && !String.valueOf(reason).isBlank()) {
            text.append("\n\n").append(texte(langue, "email.SLOT_CANCELLED.reason",
                "Motif indiqué par l'organisateur : " + reason, String.valueOf(reason)));
        }

        Object alternatives = payload.get("alternativesCount");
        if (alternatives instanceof Number count && count.intValue() > 0) {
            // Le nombre passe en texte : MessageFormat écrirait « 1 000 » selon la langue.
            String nombre = String.valueOf(count.intValue());
            text.append("\n\n").append(count.intValue() > 1
                ? texte(langue, "email.SLOT_CANCELLED.alternatives.many",
                    nombre + " autres créneaux de la même activité ont lieu près de chez vous.", nombre)
                : texte(langue, "email.SLOT_CANCELLED.alternatives.one",
                    nombre + " autre créneau de la même activité a lieu près de chez vous.", nombre));
        }

        return text.toString();
    }

    /**
     * Le texte traduit de la clé dans la langue du destinataire, ou le français
     * écrit ici à défaut.
     *
     * <p><b>Pourquoi un repli et non un appel direct.</b> {@code getOrNullIn}
     * rend {@code null} sur une clé absente là où {@code getIn} lèverait, et le
     * lèverait <b>dans un envoi asynchrone</b> — l'e-mail d'une annulation serait
     * perdu par une clé manquante. Le même patron que
     * {@code GlobalExceptionHandler.messageOf}, pour la même raison. Les clés
     * {@code email.*} des notifications sont posées dans les trois bundles depuis
     * P-BL-20.
     */
    private String texte(java.util.Locale langue, String cle, String repliFrancais, Object... args) {
        String traduit = messages.getOrNullIn(langue, cle, args);
        return traduit != null ? traduit : repliFrancais;
    }

    /**
     * Version HTML du même texte. Les valeurs sont échappées : elles viennent de
     * l'organisateur — titre du programme, motif d'annulation — et le HTML est
     * assemblé par concaténation, ce qui n'échappe rien tout seul.
     */
    private String htmlFor(java.util.Locale langue, NotificationType type, String programTitle, String text) {
        return GabaritEmail.titre(escape(subjectFor(langue, type, programTitle)))
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
            nonEnvoye("consentement de contact de confiance",
                "lien " + pageUrl + " pour " + email);
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
            log.error("Failed to send guardian consent email to {}", Masque.email(email));
        }
    }

    /**
     * Le repli quand aucun fournisseur n'est configuré — <b>la seule porte</b>
     * par laquelle un lien d'e-mail peut atteindre les journaux, et elle est
     * fermée partout sauf en développement.
     *
     * <p><b>Pourquoi une seule méthode pour cinq appels.</b> Les cinq replis
     * écrivaient chacun sa ligne, et il a suffi que la fiche d'audit en relise
     * une pour découvrir les quatre autres. Une porte unique se referme d'un
     * seul geste, et un sixième repli écrit demain passera par elle sans que son
     * auteur ait à connaître le drapeau.
     *
     * <p><b>Ce que la branche fermée n'écrit pas : rien du destinataire.</b> Ni
     * le lien, ni le jeton, ni l'adresse — pas même masquée par
     * {@link Masque#email} : une adresse partiellement lisible reste une donnée
     * personnelle, et elle n'apporterait rien ici. L'absence de fournisseur est une panne de configuration globale, la
     * même pour tout le monde : nommer un destinataire n'aide personne à la
     * diagnostiquer, et le savoir coûte une donnée personnelle par e-mail
     * tenté.
     *
     * @param quoi   ce qui n'est pas parti, en libellé fixe — jamais une adresse,
     *               jamais un lien, jamais un jeton
     * @param detail le lien et son destinataire, écrits <b>seulement</b> si
     *               {@code pair.email.journaliser-liens} est allumé, c'est-à-dire
     *               en développement et nulle part ailleurs
     */
    private void nonEnvoye(String quoi, String detail) {
        if (journaliserLiens) {
            log.info("[DEV] {} : {}", quoi, detail);
            return;
        }
        log.info("E-mail non envoyé, aucun fournisseur d'e-mail configuré : {}", quoi);
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
