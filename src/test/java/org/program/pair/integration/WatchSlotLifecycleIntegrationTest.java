package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.Notification;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchEvent;
import org.program.pair.domain.watch.WatchEventType;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.NotificationRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Une séance annulée referme ses veilles, et le proche n'est alerté pour rien.
 *
 * <p><b>Le défaut.</b> L'échéance d'une veille est figée à l'armement, et rien ne
 * la reliait au sort du créneau : ni l'annulation, ni la suppression avec inscrits,
 * ni la boucle retour ne regardaient {@code schedules.status}. Une séance annulée
 * laissait donc sa veille armée, et le minuteur faisait son travail à l'heure dite
 * — trois rappels à quelqu'un qui n'est jamais parti, puis un message d'alerte à
 * son contact d'urgence. C'est la fausse alerte que ce module existe pour ne pas
 * produire.
 *
 * <p>Trois portes sont fermées ici, et il en faut trois : l'annulation
 * ({@code POST /api/slots/{id}/cancel}), la suppression avec inscrits
 * ({@code DELETE .../schedules/{id}}, qui annule au lieu de supprimer), et le
 * <b>filet</b> dans la boucle elle-même, pour une annulation arrivée par un chemin
 * qui ne referme pas — une reprise de données, ou du code écrit avant celui-ci.
 *
 * <p>Comme partout ici, la base est partagée par toute la suite : chaque méthode
 * crée ses comptes, son créneau et sa veille.
 */
class WatchSlotLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired WatchEventRepository watchEventRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void annulerLaSeance_doitRefermerLaVeilleArmee_sansRienEnvoyerAuContact() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID watchId = armer(moi, scheduleId);
        arriver(moi, watchId);
        assertThat(etat(watchId)).isEqualTo(WatchState.ON_SITE);

        annuler(moi, scheduleId);

        // Refermée dans la transaction de l'annulation, avec sa trace : la
        // chronologie est une preuve, et une veille qui se refermerait sans
        // événement ne pourrait plus être relue après coup.
        assertThat(etat(watchId)).isEqualTo(WatchState.CLOSED);
        assertThat(watch(watchId).getClosedAt()).isNotNull();
        assertThat(typesDeLaChronologie(watchId)).contains(WatchEventType.ABANDONED);

        // Et l'échéance passée ne réveille plus rien : la veille est sortie du champ
        // du balayage, donc aucun rappel, aucune escalade, aucun message.
        reculerEcheance(watchId, 90);
        returnLoopJob.tick();

        assertThat(etat(watchId)).isEqualTo(WatchState.CLOSED);
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
            .until(() -> notificationsDe(moi, NotificationType.WATCH_RETURN_REMINDER).isEmpty());
    }

    /**
     * Une alerte déjà sortie reste à lever par la personne.
     *
     * <p>Elle a peut-être un vrai souci — une séance annulée n'empêche personne
     * d'être parti quelque part — et son contact, qui a reçu le message, ne doit pas
     * rester sans nouvelle. Refermer d'office effacerait l'alerte côté serveur sans
     * jamais envoyer la levée.
     */
    @Test
    void annulerLaSeance_neDoitPasToucherUneVeilleDejaEscaladee() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID watchId = armer(moi, scheduleId);
        arriver(moi, watchId);
        forcerLetat(watchId, WatchState.ESCALATED);

        annuler(moi, scheduleId);

        assertThat(etat(watchId)).isEqualTo(WatchState.ESCALATED);
        assertThat(watch(watchId).getClosedAt()).isNull();
        assertThat(typesDeLaChronologie(watchId)).doesNotContain(WatchEventType.ABANDONED);
    }

    /**
     * Le chemin {@code DELETE}, qui annule au lieu de supprimer quand des personnes
     * comptent sur la séance. Il n'appelle pas {@code SlotCancellationService} —
     * P-BL-19 unifiera les deux — et devait donc refermer lui-même.
     */
    @Test
    void supprimerLaSeanceAvecInscrits_doitRefermerLaVeille() {
        Compte hote = compte();
        Compte inscrit = compte();
        Creneau creneau = creerCreneauComplet(hote);

        webTestClient.post().uri("/api/slots/{id}/join", creneau.scheduleId())
            .headers(h -> h.setBearerAuth(inscrit.token()))
            .exchange().expectStatus().isCreated();

        UUID watchId = armer(inscrit, creneau.scheduleId());

        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}",
                creneau.programId(), creneau.scheduleId())
            .headers(h -> h.setBearerAuth(hote.token()))
            .exchange().expectStatus().isNoContent();

        // Le créneau est annulé et non supprimé (il a un inscrit), et la veille
        // de l'inscrit est refermée avec lui.
        assertThat(creneau(creneau.scheduleId()).getStatus()).isEqualTo(SlotStatus.CANCELLED);
        assertThat(etat(watchId)).isEqualTo(WatchState.CLOSED);
        assertThat(typesDeLaChronologie(watchId)).contains(WatchEventType.ABANDONED);
    }

    /**
     * On n'arme pas sur une séance qui n'aura pas lieu. « Introuvable », même forme
     * que le refus d'appartenance : la veille n'a pas à confirmer ce que ce créneau
     * a été.
     */
    @Test
    void armerUneVeille_surUneSeanceAnnulee_doitEtreRefuse() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi);

        annuler(moi, scheduleId);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                              "guardianId", guardianId.toString()))
            .exchange().expectStatus().isNotFound();
    }

    /**
     * Le filet de la boucle : une annulation qui n'est pas passée par la clôture.
     *
     * <p>Le statut du créneau est basculé <b>en base directement</b>, et c'est tout
     * l'objet du test : on simule ce que la clôture ne peut pas rattraper — une
     * annulation arrivée entre deux passages par un chemin qui l'ignore, ou une
     * ligne écrite avant ce lot. Passer par l'endpoint d'annulation refermerait
     * déjà la veille et ne prouverait rien du filet.
     */
    @Test
    void uneAnnulationEntreDeuxPassages_neDoitPasProduireDeRappel() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID watchId = armer(moi, scheduleId);
        arriver(moi, watchId);

        annulerLeCreneauEnBase(scheduleId);
        reculerEcheance(watchId, 16); // un rappel serait dû

        returnLoopJob.tick();

        assertThat(etat(watchId)).isEqualTo(WatchState.CLOSED);
        assertThat(watch(watchId).getRemindersSent()).isZero();
        assertThat(typesDeLaChronologie(watchId))
            .contains(WatchEventType.ABANDONED)
            .doesNotContain(WatchEventType.REMINDER_SENT);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
            .until(() -> notificationsDe(moi, NotificationType.WATCH_RETURN_REMINDER).isEmpty());
    }

    // ------------------------------------------------------------------ outils

    private WatchState etat(UUID watchId) {
        return watch(watchId).getState();
    }

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private Schedule creneau(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId).orElseThrow();
    }

    private List<WatchEventType> typesDeLaChronologie(UUID watchId) {
        return watchEventRepository.findByWatchIdOrderByOccurredAtAsc(watchId).stream()
            .map(WatchEvent::getType)
            .toList();
    }

    private List<Notification> notificationsDe(Compte qui, NotificationType type) {
        return notificationRepository.findByUserId(qui.id()).stream()
            .filter(n -> n.getType() == type)
            .toList();
    }

    private void reculerEcheance(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setDeadlineAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /** Pose un état que les verbes publics ne permettent pas d'atteindre ici. */
    private void forcerLetat(UUID watchId, WatchState etat) {
        Watch w = watch(watchId);
        w.setState(etat);
        watchRepository.saveAndFlush(w);
    }

    /** Annule sans passer par la clôture : uniquement ce créneau, jamais un autre. */
    private void annulerLeCreneauEnBase(UUID scheduleId) {
        Schedule slot = creneau(scheduleId);
        slot.setStatus(SlotStatus.CANCELLED);
        slot.setCancelledAt(Instant.now());
        scheduleRepository.saveAndFlush(slot);
    }

    private void annuler(Compte organisateur, UUID scheduleId) {
        webTestClient.post().uri("/api/slots/{id}/cancel", scheduleId)
            .headers(h -> h.setBearerAuth(organisateur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("reason", "La salle a fermé."))
            .exchange().expectStatus().isNoContent();
    }

    private UUID armer(Compte owner, UUID scheduleId) {
        UUID guardianId = contactAccepte(owner);
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                              "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private void arriver(Compte owner, UUID watchId) {
        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange().expectStatus().isOk();
    }

    private record Creneau(UUID scheduleId, UUID programId) {}

    private UUID creerCreneau(Compte owner) {
        return creerCreneauComplet(owner).scheduleId();
    }

    private Creneau creerCreneauComplet(Compte owner) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.HOURS), null,
                "Studio Lumière", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return new Creneau(
            UUID.fromString(String.valueOf(body.get("scheduleId"))),
            UUID.fromString(String.valueOf(body.get("programId"))));
    }

    private UUID contactAccepte(Compte owner) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "phone", uniqueMobile(),
                              "email", uniqueEmail("proche")))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return guardianId;
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("cycle");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Cycle" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();

        UUID id = UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(auth.accessToken()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        return new Compte(id, auth.accessToken());
    }
}
