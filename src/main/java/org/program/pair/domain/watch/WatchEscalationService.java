package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            prevenirLeContact(watch.getGuardianId(), watch.getId(), contexte(watch));
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
            prevenirLeContact(watch.getBackupGuardianId(), watch.getId(), contexte(watch));
            inscrire(watch.getId(), WatchEventType.BACKUP_ALERTED, Instant.now());
            agi = true;
        }
        return agi;
    }

    // ------------------------------------------------------------ non-arrivée

    /**
     * Perdu en chemin : trois demandes d'arrivée sans réponse.
     *
     * <p>Trois choses partent, et une seule ne part pas. L'organisateur reçoit une
     * notification in-app — le nom, l'absence de validation, l'heure — et rien
     * d'autre : ni le lieu de départ, ni le contact, ni la position ne le
     * regardent, et il ne reçoit aucun des SMS d'alerte. Le contact reçoit le
     * message ⑤ (non-arrivée, distincte de la non-retour). Un incident est
     * journalisé. Ce qui ne part <b>jamais</b>, c'est une ligne {@code Attendance} :
     * un perdu en chemin ne compte ni comme une absence, ni contre la fiabilité, la
     * série ou les badges — sinon le produit punit quelqu'un pour un incident de
     * sécurité.
     *
     * <p>Idempotent : l'incident déjà journalisé pour cette veille tient lieu de
     * garde.
     */
    public void escalateNonArrival(Watch watch) {
        if (incidentRepository.existsByWatchId(watch.getId())) {
            return;
        }
        if (watch.getPublicToken() == null) {
            watch.setPublicToken(ShareToken.nextUnique(watchRepository::existsByPublicToken));
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

        // Le contact : message ⑤, SMS et e-mail selon les canaux. Le lieu de départ
        // n'est pas connu (aucune adresse de domicile stockée) ; l'heure de départ
        // est celle de l'armement.
        prevenirNonArrivee(watch.getGuardianId(), watch.getId(), ctx, watch.getArmedAt());

        // L'incident, jamais une absence.
        incidentRepository.save(Incident.lostOnTheWay(
            watch.getUserId(), watch.getId(), watch.getScheduleId()));

        watch.setState(WatchState.ESCALATED);
        inscrire(watch.getId(), WatchEventType.LOST_ON_THE_WAY, Instant.now());
    }

    private void prevenirNonArrivee(UUID guardianId, UUID watchId,
                                    AlertMessages.Contexte ctx, Instant heureDepart) {
        Guardian guardian = guardianRepository.findById(guardianId).orElse(null);
        if (guardian == null) {
            return;
        }
        if (guardian.isMember()) {
            notificationService.notify(guardian.getMemberId(), NotificationType.WATCH_GUARDIAN_ALERT,
                Map.of("watchId", watchId.toString(), "lien", ctx.lienStatut()));
            return;
        }
        if (smsEnabled && notBlank(guardian.getPhone())) {
            outbox.enqueueSms(guardian.getPhone(),
                AlertMessages.nonArriveeSms(ctx, null, heureDepart),
                OutboxService.PRIORITE_ALERTE, watchId);
        }
        if (notBlank(guardian.getEmail())) {
            String desabonnement = publicBaseUrl + "/public/guardian-consent/" + guardian.getConsentToken();
            outbox.enqueueEmail(guardian.getEmail(), "Non-arrivée — meetDo",
                AlertMessages.nonArriveeEmailHtml(ctx, null, heureDepart, desabonnement),
                OutboxService.PRIORITE_EMAIL, watchId);
        }
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
    }

    // ------------------------------------------------------------------ outils

    private void prevenirLeContact(UUID guardianId, UUID watchId, AlertMessages.Contexte ctx) {
        Guardian guardian = guardianRepository.findById(guardianId).orElse(null);
        if (guardian == null) {
            log.warn("Contact {} de la veille {} introuvable à l'escalade", guardianId, watchId);
            return;
        }

        if (guardian.isMember()) {
            // Contact qui a un compte : alerte in-app, plus un e-mail à son adresse.
            notificationService.notify(guardian.getMemberId(), NotificationType.WATCH_GUARDIAN_ALERT,
                Map.of("watchId", watchId.toString(), "lien", ctx.lienStatut()));
            userRepository.findById(guardian.getMemberId())
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .ifPresent(email -> outbox.enqueueEmail(email, "Alerte retour — meetDo",
                    AlertMessages.alerteRetourEmailHtml(ctx, ctx.lienStatut()),
                    OutboxService.PRIORITE_EMAIL, watchId));
            return;
        }

        // Contact externe : e-mail, et SMS en parallèle si le canal est actif.
        String desabonnement = publicBaseUrl + "/public/guardian-consent/" + guardian.getConsentToken();
        if (smsEnabled && notBlank(guardian.getPhone())) {
            outbox.enqueueSms(guardian.getPhone(), AlertMessages.alerteRetourSms(ctx),
                OutboxService.PRIORITE_ALERTE, watchId);
        }
        if (notBlank(guardian.getEmail())) {
            outbox.enqueueEmail(guardian.getEmail(), "Alerte retour — meetDo",
                AlertMessages.alerteRetourEmailHtml(ctx, desabonnement),
                OutboxService.PRIORITE_EMAIL, watchId);
        }
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
