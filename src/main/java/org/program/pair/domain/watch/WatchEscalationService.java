package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.guardian.ConsentState;
import org.program.pair.domain.guardian.Guardian;
import org.program.pair.domain.incident.Incident;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.user.GivenName;
import org.program.pair.domain.user.User;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qui part quand une veille bascule : les rappels à la personne, l'alerte aux
 * contacts, et la levée quand tout finit bien.
 *
 * <p>Séparé du minuteur qui le déclenche, et de la clôture qui l'appelle pour la
 * levée : la décision « quand » appartient à l'horloge, la décision « quoi »
 * appartient ici. Tout ce qui sort par SMS ou e-mail passe par l'outbox — écrit en
 * base dans la transaction courante, envoyé ensuite par le balayage — pour survivre
 * à un redéploiement et partir en parallèle, jamais en cascade.
 *
 * <p>Le lien d'urgence naît ici, à l'escalade, et pas à l'armement : sans quoi le
 * contact verrait chaque soirée de quelqu'un.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class WatchEscalationService {

    private final WatchRepository watchRepository;
    private final WatchEventRepository eventRepository;
    private final GuardianRepository guardianRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final OutboxService outbox;
    private final OutboxMessageRepository outboxRepository;
    private final NotificationService notificationService;
    private final org.program.pair.repository.IncidentRepository incidentRepository;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    /**
     * Le canal SMS est-il actif ? <b>Éteint par défaut.</b> La décision d'envoyer
     * des SMS n'est pas tranchée ; en attendant, les alertes ne partent que par
     * e-mail. L'infrastructure SMS (abstraction, outbox, gabarits) reste en place
     * et prête : poser {@code WATCH_SMS_ENABLED=true} — et brancher un vrai
     * fournisseur — suffira à l'allumer, sans retoucher ce code.
     *
     * <p>Cela n'exclut personne : un contact externe accepté a forcément un
     * e-mail, puisque l'invitation d'un contact qui n'a qu'un téléphone est déjà
     * refusée à la priorité 1.
     */
    @Value("${pair.watch.sms.enabled:false}")
    private boolean smsEnabled;

    // ------------------------------------------------------------------ rappels

    /** Un rappel de retour à la personne veillée. Push time-sensitive, inscrit à la chronologie. */
    public void sendReminder(Watch watch) {
        notificationService.notify(watch.getUserId(), NotificationType.WATCH_RETURN_REMINDER,
            Map.of("watchId", watch.getId().toString(),
                   "deadlineAt", watch.getDeadlineAt().toString()));
        inscrire(watch.getId(), WatchEventType.REMINDER_SENT, Instant.now());
    }

    /** Une demande « tu y es ? » à la personne, sur le trajet aller. */
    public void sendArrivalPrompt(Watch watch) {
        notificationService.notify(watch.getUserId(), NotificationType.WATCH_ARRIVAL_PROMPT,
            Map.of("watchId", watch.getId().toString()));
        inscrire(watch.getId(), WatchEventType.ARRIVAL_PROMPTED, Instant.now());
    }

    // ------------------------------------------------------------------ alerte

    /**
     * S'assure qu'une veille escaladée a bien prévenu ses contacts.
     *
     * <p><b>Un seul point d'envoi des alertes, pour deux chemins d'entrée.</b> Une
     * veille passe {@code ESCALATED} de deux façons : par le minuteur, après trois
     * rappels sans réponse ; ou par une clôture sous contrainte, qui pose l'état
     * sans rien envoyer dans sa transaction — pour que la réponse reste
     * indiscernable d'une clôture normale. Dans les deux cas, c'est ici, hors de la
     * transaction de réponse, que le message ② part. L'idempotence tient à ce
     * qu'on vérifie d'abord qu'aucun message n'a déjà été déposé pour cette veille.
     *
     * <p>Le contact de secours suit, une fois la fenêtre atteinte, si le principal
     * n'a rien ouvert. La condition « rien ouvert » repose sur l'accusé de lecture
     * de la page publique (priorité 5) ; d'ici là, le secours est prévenu dès la
     * fenêtre franchie. L'événement {@code BACKUP_ALERTED} garde le compte.
     *
     * @param ecouleMinutes minutes écoulées depuis l'échéance, pour la fenêtre du secours
     */
    public boolean ensureAlerted(Watch watch, long ecouleMinutes) {
        boolean agi = false;

        // ensureAlerted ne tourne que sur une veille ESCALATED, avant toute levée
        // (qui n'a lieu qu'à la clôture, en RESOLVED) : tout message déjà déposé
        // pour cette veille est donc une alerte, et sa présence dit que le contact
        // principal a déjà été prévenu.
        boolean alerteDejaPartie = !outboxRepository.findByWatchId(watch.getId()).isEmpty();

        if (!alerteDejaPartie) {
            if (watch.getPublicToken() == null) {
                watch.setPublicToken(ShareToken.nextUnique(watchRepository::existsByPublicToken));
            }
            prevenirLeContact(watch.getGuardianId(), watch.getId(), contexte(watch), "principal");
            if (!eventRepository.existsByWatchIdAndType(watch.getId(), WatchEventType.ESCALATED)) {
                inscrire(watch.getId(), WatchEventType.ESCALATED, Instant.now());
            }
            agi = true;
        }

        // Le contact de secours, à sa fenêtre — mais seulement « si le principal
        // n'a rien ouvert ». Une ouverture de la page publique avant cette fenêtre
        // vaut réponse du principal, le seul à détenir le lien avant que le secours
        // ne soit prévenu : on ne le dérange alors pas.
        if (watch.getBackupGuardianId() != null
                && ecouleMinutes >= 75
                && watch.getPublicViewedAt() == null
                && !eventRepository.existsByWatchIdAndType(watch.getId(), WatchEventType.BACKUP_ALERTED)) {
            prevenirLeContact(watch.getBackupGuardianId(), watch.getId(), contexte(watch), "secours");
            inscrire(watch.getId(), WatchEventType.BACKUP_ALERTED, Instant.now());
            agi = true;
        }
        return agi;
    }

    // ------------------------------------------------------------ non-arrivée

    /**
     * Perdu en chemin : trois demandes d'arrivée sans réponse.
     *
     * <p><b>Personne n'est prévenu, et c'est la décision du 02/09.</b> Quelqu'un qui
     * n'a jamais validé son arrivée ne fait plus prévenir son contact d'urgence :
     * personne n'est parti, il n'y a ni trajet à surveiller, ni dernier signe de vie
     * à transmettre, ni lieu où chercher. Le gabarit ⑤ ne part plus, et
     * <b>aucun jeton public n'est créé</b> — le contrat dit « le lien naît à
     * l'alerte » ; sans alerte, un jeton qui existerait sans destinataire ne
     * pourrait que fuir. Le prix est assumé : quelqu'un à qui il arrive réellement
     * quelque chose <em>en chemin</em> ne sera pas signalé par la veille.
     *
     * <p>Restent deux choses. L'organisateur reçoit une notification in-app — le
     * nom, l'absence de validation, l'heure — et rien d'autre : ni le lieu de
     * départ, ni le contact, ni la position ne le regardent. Un incident est
     * journalisé. Ce qui ne part <b>jamais</b>, c'est une ligne {@code Attendance} :
     * un perdu en chemin ne compte ni comme une absence, ni contre la fiabilité, la
     * série ou les badges — sinon le produit punit quelqu'un pour un incident de
     * sécurité.
     *
     * <p><b>L'état posé est {@link WatchState#NOT_ARRIVED}, pas {@code ESCALATED}, et
     * c'est ce qui empêche l'alerte de repartir par la bande.</b> {@code ESCALATED}
     * est balayé par {@code WatchReturnLoopJob} : la veille y aurait été reprise à
     * son échéance — fin de créneau plus une heure — et {@code ensureAlerted} aurait
     * envoyé l'alerte retour ②, puis le contact de secours à +75 min. Le seul
     * garde-fou qui l'en empêchait jusqu'ici était l'outbox non vide, c'est-à-dire
     * un effet de bord du message ⑤ qu'on vient de retirer — et il ne tenait déjà
     * pas quand le contact est un membre, ⑤ ne déposant alors rien dans l'outbox.
     *
     * <p>Idempotent : l'incident déjà journalisé pour cette veille tient lieu de
     * garde.
     */
    public void escalateNonArrival(Watch watch) {
        if (incidentRepository.existsByWatchId(watch.getId())) {
            return;
        }

        AlertMessages.Contexte ctx = contexte(watch);

        // L'organisateur, en in-app seulement, et sans rien qui ne le regarde pas.
        Schedule slot = scheduleRepository.findById(watch.getScheduleId()).orElse(null);
        UUID organisateur = organisateurDe(slot);
        if (organisateur != null && !organisateur.equals(watch.getUserId())) {
            notificationService.notify(organisateur, NotificationType.WATCH_LOST_ORGANIZER,
                Map.of("watchId", watch.getId().toString(),
                       "personne", ctx.prenomNom(),
                       "heure", watch.getOccurrenceStartsAt() != null
                           ? watch.getOccurrenceStartsAt().toString() : ""));
        }

        // L'incident, jamais une absence.
        incidentRepository.save(Incident.lostOnTheWay(
            watch.getUserId(), watch.getId(), watch.getScheduleId()));

        // Refermée côté serveur : il n'y a plus rien à surveiller, et sans cela la
        // veille resterait ouverte indéfiniment — la boucle aller ne balaie que
        // ARMED et EN_ROUTE. closedAt date aussi la fenêtre de 24 h pendant laquelle
        // « mes veilles actives » la rend encore, pour que la personne l'apprenne.
        Instant now = Instant.now();
        watch.setState(WatchState.NOT_ARRIVED);
        watch.setClosedAt(now);
        inscrire(watch.getId(), WatchEventType.LOST_ON_THE_WAY, now);

        // Inscrit aussi ce qui NE part pas : c'est la trace qui dit qu'une
        // non-arrivée s'est bien tue, et elle vaut celle d'un envoi.
        log.info("Veille {} : non-arrivée, refermée. Organisateur {}, contact d'urgence non prévenu",
            watch.getId(), organisateur != null ? "prévenu" : "absent");
    }

    private static UUID organisateurDe(Schedule slot) {
        try {
            return slot.getProgram().getUserActivity().getUser().getId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- levée

    /**
     * La levée, message ③ : la personne a fini par confirmer après une alerte.
     *
     * <p>« Même canal, même fil » : la levée repart exactement là où l'alerte est
     * allée. On relit les messages déjà déposés pour cette veille et l'on renvoie
     * un message de levée à chaque destinataire, sur son canal. Non facultative —
     * quelqu'un réveillé par ② doit apprendre que l'alerte est levée.
     */
    public void sendLevee(Watch watch) {
        AlertMessages.Contexte ctx = contexte(watch);
        java.util.Set<String> deja = new java.util.HashSet<>();

        for (OutboxMessage alerte : outboxRepository.findByWatchId(watch.getId())) {
            // Une levée par destinataire distinct, sur son canal — la levée repart
            // exactement là où l'alerte est allée, sans doublon si deux messages
            // partageaient un destinataire.
            if (!deja.add(alerte.getChannel() + "|" + alerte.getRecipient())) {
                continue;
            }
            switch (alerte.getChannel()) {
                case SMS -> outbox.enqueueSms(alerte.getRecipient(), AlertMessages.leveeSms(ctx),
                    OutboxService.PRIORITE_ALERTE, watch.getId());
                case EMAIL -> outbox.enqueueEmail(alerte.getRecipient(),
                    "Fausse alerte — tout va bien", AlertMessages.leveeEmailHtml(ctx),
                    OutboxService.PRIORITE_ALERTE, watch.getId());
            }
        }
        inscrire(watch.getId(), WatchEventType.LEVEE_SENT, Instant.now());
        log.info("Veille {} : LEVEE partie à {} destinataire(s)", watch.getId(), deja.size());
    }

    /**
     * ⑦ Le renoncement : la personne a renoncé à s'y rendre, après qu'un message
     * est parti à son contact.
     *
     * <p><b>Même mécanique que la levée, autre texte, et le texte est tout le
     * sujet.</b> On repart sur le canal exact où l'alerte est allée — « même canal,
     * même fil » — mais ③ dit « vient de confirmer son retour », ce qui est faux de
     * quelqu'un qui n'est jamais parti. Envoyer ③ ici serait vrai sur l'essentiel et
     * faux sur les faits : le même défaut que « Bien rentrée » sur la page publique,
     * en plus discret, et de ceux que personne ne vient vérifier parce qu'ils
     * annoncent une bonne nouvelle.
     *
     * <p>N'a de sens qu'après un envoi : sur une veille dont l'outbox est vide, rien
     * ne part et l'appelant n'a pas à s'en soucier.
     */
    public void sendRenoncement(Watch watch) {
        AlertMessages.Contexte ctx = contexte(watch);
        java.util.Set<String> deja = new java.util.HashSet<>();

        for (OutboxMessage alerte : outboxRepository.findByWatchId(watch.getId())) {
            if (!deja.add(alerte.getChannel() + "|" + alerte.getRecipient())) {
                continue;
            }
            switch (alerte.getChannel()) {
                case SMS -> outbox.enqueueSms(alerte.getRecipient(),
                    AlertMessages.renoncementSms(ctx),
                    OutboxService.PRIORITE_ALERTE, watch.getId());
                case EMAIL -> outbox.enqueueEmail(alerte.getRecipient(),
                    AlertMessages.renoncementObjet(ctx),
                    AlertMessages.renoncementEmailHtml(ctx),
                    OutboxService.PRIORITE_ALERTE, watch.getId());
            }
        }
        if (!deja.isEmpty()) {
            inscrire(watch.getId(), WatchEventType.LEVEE_SENT, Instant.now());
            log.info("Veille {} : RENONCEMENT parti à {} destinataire(s)",
                watch.getId(), deja.size());
        }
    }

    /**
     * ⑥ « Je suis bien rentrée » — annonce demandée par la personne veillée.
     *
     * <p><b>Ce que cette méthode n'est pas.</b> Elle n'est pas une notification de
     * fin de veille, et rien ici ne l'appelle de lui-même : elle ne part que sur un
     * drapeau explicite de {@code CloseRequest}, faux par défaut. La règle qui
     * interdit au système d'apprendre à un tiers qu'une veille s'est terminée tient
     * entière — aucun {@code NotificationType} n'a été créé, et le test qui garde le
     * catalogue reste vrai.
     *
     * <p><b>Au contact principal seulement</b>, jamais au suppléant : le suppléant
     * n'est sollicité que lorsque le premier n'a pas répondu à une alerte, et il n'a
     * ici rien à apprendre. Jamais non plus à l'organisateur, qui ne voit que des
     * arrivées.
     *
     * <p><b>Les échecs sont avalés.</b> Un message d'agrément qui ne part pas ne doit
     * pas faire échouer une clôture : la veille est refermée, c'est ce qui compte.
     *
     * <p><b>Le message n'est PAS rattaché à la veille dans l'outbox</b>, et c'est
     * délibéré. {@code alertDelivery} agrège l'état de remise de tout ce que
     * l'outbox porte pour une veille, et le client en a fait un bandeau global :
     * un {@code BOUNCED} y signifie « le proche n'a pas été joint par l'alerte ».
     * Rattacher cette annonce ferait passer à {@code SENT} une veille où aucune
     * alerte n'est jamais partie, et afficherait le bandeau d'alarme parce qu'un
     * « tout va bien » a rebondi. La trace de l'envoi est ailleurs, à sa place :
     * l'événement {@code RETURN_ANNOUNCED} du journal.
     */
    public void annoncerLeRetour(Watch watch) {
        if (watch.getGuardianId() == null) {
            return;
        }
        try {
            Guardian guardian = guardianRepository.findById(watch.getGuardianId()).orElse(null);
            if (guardian == null || guardian.getConsentState() != ConsentState.ACCEPTED) {
                // Un contact qui n'a pas accepté d'être contact ne reçoit rien, pas
                // même une bonne nouvelle.
                return;
            }

            AlertMessages.Contexte ctx = contexte(watch);
            boolean envoye = false;

            if (guardian.isMember()) {
                // Contact membre : son adresse, et rien d'in-app — une notification
                // in-app est précisément ce que la règle du module interdit ici.
                String email = userRepository.findById(guardian.getMemberId())
                    .map(User::getEmail).filter(e -> e != null && !e.isBlank()).orElse(null);
                if (email != null) {
                    outbox.enqueueEmail(email, "Tout va bien",
                        AlertMessages.retourAnnonceEmailHtml(ctx),
                        OutboxService.PRIORITE_EMAIL, null);
                    envoye = true;
                }
            } else {
                if (smsEnabled && notBlank(guardian.getPhone())) {
                    outbox.enqueueSms(guardian.getPhone(), AlertMessages.retourAnnonceSms(ctx),
                        OutboxService.PRIORITE_EMAIL, null);
                    envoye = true;
                }
                if (notBlank(guardian.getEmail())) {
                    outbox.enqueueEmail(guardian.getEmail(), "Tout va bien",
                        AlertMessages.retourAnnonceEmailHtml(ctx),
                        OutboxService.PRIORITE_EMAIL, null);
                    envoye = true;
                }
            }

            if (envoye) {
                inscrire(watch.getId(), WatchEventType.RETURN_ANNOUNCED, Instant.now());
                inscrireAuJournal(watch.getId(), "ANNONCE_RETOUR", "principal",
                    guardian.getId(), "email");
            }
        } catch (RuntimeException e) {
            log.warn("Annonce de retour non partie pour la veille {} : {}",
                watch.getId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ outils

    private void prevenirLeContact(UUID guardianId, UUID watchId, AlertMessages.Contexte ctx,
                                   String role) {
        Guardian guardian = guardianRepository.findById(guardianId).orElse(null);
        if (guardian == null) {
            log.warn("Contact {} de la veille {} introuvable à l'escalade", guardianId, watchId);
            return;
        }

        if (guardian.isMember()) {
            // Contact qui a un compte : alerte in-app, plus un e-mail à son adresse.
            notificationService.notify(guardian.getMemberId(), NotificationType.WATCH_GUARDIAN_ALERT,
                Map.of("watchId", watchId.toString(), "lien", ctx.lienStatut()));
            boolean parCourrier = userRepository.findById(guardian.getMemberId())
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .map(email -> {
                    outbox.enqueueEmail(email, "Alerte retour — meetDo",
                        AlertMessages.alerteRetourEmailHtml(ctx, ctx.lienStatut()),
                        OutboxService.PRIORITE_EMAIL, watchId);
                    return true;
                }).orElse(false);
            inscrireAuJournal(watchId, "ALERTE_RETOUR", role, guardianId,
                parCourrier ? "in-app+email" : "in-app");
            return;
        }

        // Contact externe : e-mail, et SMS en parallèle si le canal est actif.
        String desabonnement = publicBaseUrl + "/public/guardian-consent/" + guardian.getConsentToken();
        boolean parSms = smsEnabled && notBlank(guardian.getPhone());
        if (parSms) {
            outbox.enqueueSms(guardian.getPhone(), AlertMessages.alerteRetourSms(ctx),
                OutboxService.PRIORITE_ALERTE, watchId);
        }
        boolean parCourrier = notBlank(guardian.getEmail());
        if (parCourrier) {
            outbox.enqueueEmail(guardian.getEmail(), "Alerte retour — meetDo",
                AlertMessages.alerteRetourEmailHtml(ctx, desabonnement),
                OutboxService.PRIORITE_EMAIL, watchId);
        }
        inscrireAuJournal(watchId, "ALERTE_RETOUR", role, guardianId, canaux(parSms, parCourrier));
    }

    /**
     * L'inscription au journal d'un envoi <b>réellement parti</b> vers un tiers.
     *
     * <p><b>Au niveau {@code info}, et c'est le point.</b> Jusqu'ici l'envoi d'une
     * alerte n'était inscrit nulle part de lisible : la notification l'était en
     * {@code debug}, que la configuration de production n'émet pas ; l'outbox ne
     * journalise que ses échecs ; les boucles ne journalisent qu'un compteur sans
     * identifiant. Un message parti normalement ne laissait donc aucune trace, et
     * répondre à « une alerte est-elle partie à ce contact, pour cette veille ? »
     * demandait de reconstituer la réponse depuis la base.
     *
     * <p><b>Aucune coordonnée n'y entre</b> — ni adresse, ni numéro, ni nom. Des
     * identifiants internes et le canal employé, rien de plus : un journal
     * d'application n'est pas l'endroit où recopier le carnet d'adresses de
     * quelqu'un, et l'audit n'en a pas besoin pour être concluant.
     */
    private void inscrireAuJournal(UUID watchId, String gabarit, String role,
                                   UUID destinataireId, String canaux) {
        log.info("Veille {} : {} parti au contact {} ({}), par {}",
            watchId, gabarit, role, destinataireId, canaux);
    }

    private static String canaux(boolean sms, boolean email) {
        if (sms && email) {
            return "sms+email";
        }
        if (sms) {
            return "sms";
        }
        return email ? "email" : "aucun canal";
    }

    private AlertMessages.Contexte contexte(Watch watch) {
        User user = userRepository.findById(watch.getUserId()).orElse(null);
        Schedule slot = scheduleRepository.findById(watch.getScheduleId()).orElse(null);

        String displayName = user != null ? user.getDisplayName() : "Une personne";
        Instant dernierSigne = watch.getArrivalConfirmedAt() != null
            ? watch.getArrivalConfirmedAt() : watch.getArmedAt();

        String lieuNom = slot != null ? slot.getPlaceName() : null;
        String ville = slot != null ? slot.getCity() : null;
        String titre = titreActivite(slot);
        Instant heureFin = slot != null ? SlotTiming.endOf(slot) : null;
        String lien = publicBaseUrl + "/public/watch/" + watch.getPublicToken();

        return new AlertMessages.Contexte(
            displayName, GivenName.from(displayName),
            watch.getDeadlineAt(), dernierSigne,
            lieuNom, ville, titre,
            watch.getOccurrenceStartsAt(), heureFin, lien);
    }

    private static String titreActivite(Schedule slot) {
        try {
            return slot.getProgram().getUserActivity().getActivity().getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void inscrire(UUID watchId, WatchEventType type, Instant quand) {
        eventRepository.save(new WatchEvent(watchId, type, quand));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
