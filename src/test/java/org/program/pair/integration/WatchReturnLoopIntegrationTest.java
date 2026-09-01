package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.outbox.OutboxChannel;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La boucle retour tenue par le serveur : rappels, escalade, contact de secours,
 * levée — et le cas qui compte le plus, l'escalade différée d'une clôture sous
 * contrainte.
 *
 * <p>On ne fait pas dépendre le test de l'horloge du planificateur : on arme
 * normalement, on recule {@code deadlineAt} en base pour rendre les jalons dus, et
 * l'on déclenche {@link WatchReturnLoopJob#tick()} à la main. C'est la logique des
 * minuteurs qu'on vérifie, pas leur cadence.
 */
class WatchReturnLoopIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void auFilDesJalons_lesRappelsPuisLescalade_partent() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0612345678", "proche@example.org");
        arriver(moi, watchId, null);
        reculerEcheance(watchId, 90); // 90 min après l'échéance : tout est dû.

        // Trois passages, trois rappels.
        returnLoopJob.tick();
        assertThat(etat(watchId)).isEqualTo(WatchState.REMINDING);
        assertThat(watch(watchId).getRemindersSent()).isEqualTo(1);
        returnLoopJob.tick();
        returnLoopJob.tick();
        assertThat(watch(watchId).getRemindersSent()).isEqualTo(3);
        // Toujours pas d'alerte tant que les trois rappels ne sont pas passés.
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();

        // Quatrième passage : l'escalade, et le message ② au contact (SMS + e-mail).
        returnLoopJob.tick();
        assertThat(etat(watchId)).isEqualTo(WatchState.ESCALATED);
        List<OutboxMessage> messages = outboxRepository.findByWatchId(watchId);
        // Canal SMS éteint par défaut : seule l'alerte e-mail est déposée.
        assertThat(messages).extracting(OutboxMessage::getChannel)
            .containsOnly(OutboxChannel.EMAIL);
        // Le lien d'urgence naît avec l'alerte, pas avant.
        assertThat(watch(watchId).getPublicToken()).isNotNull();
    }

    @Test
    void uneClotureSousContrainte_faitPartirLescaladeAuPassageSuivant() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0698765432", "contrainte@example.org");

        // La personne fixe son code de contrainte en validant son arrivée, puis
        // referme avec — la réponse est un 202 ordinaire, et rien n'est encore
        // parti dans sa transaction.
        String duress = "SESAME";
        arriver(moi, watchId, duress);
        webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", duress, "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();

        assertThat(etat(watchId)).isEqualTo(WatchState.ESCALATED);
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty(); // rien dans la transaction de réponse

        // Le minuteur reprend la veille escaladée et fait partir l'alerte.
        reculerEcheance(watchId, 10);
        returnLoopJob.tick();
        assertThat(outboxRepository.findByWatchId(watchId))
            .extracting(OutboxMessage::getChannel)
            .containsOnly(OutboxChannel.EMAIL);
    }

    @Test
    void unBonCodeApresLescalade_leveLalerte_etEnvoieLaLevee() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0611223344", "levee@example.org");
        String code = arriver(moi, watchId, null); // le vrai code
        reculerEcheance(watchId, 90);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick(); // jusqu'à l'escalade
        }
        assertThat(etat(watchId)).isEqualTo(WatchState.ESCALATED);
        int alertes = outboxRepository.findByWatchId(watchId).size();

        // La personne finit par confirmer avec le vrai code : levée.
        webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();

        assertThat(etat(watchId)).isEqualTo(WatchState.RESOLVED);
        // La levée repart là où l'alerte est allée : de nouveaux messages, en plus.
        assertThat(outboxRepository.findByWatchId(watchId).size()).isGreaterThan(alertes);
    }

    // ------------------------------------------------------------------ outils

    private WatchState etat(UUID watchId) {
        return watch(watchId).getState();
    }

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private void reculerEcheance(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setDeadlineAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /** Une veille armée, avec un contact accepté joignable — sans arrivée encore. */
    private UUID armer(Compte owner, String phone, String email) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner, phone, email);
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    /** Valide l'arrivée, éventuellement avec un code de contrainte, et rend le code de retour. */
    private String arriver(Compte owner, UUID watchId, String duress) {
        Object body = duress == null ? Map.of() : Map.of("duressCode", duress);
        return String.valueOf(webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
    }

    private UUID creerCreneau(Compte owner) {
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
        return UUID.fromString(String.valueOf(body.get("scheduleId")));
    }

    private UUID contactAccepte(Compte owner, String phone, String email) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "phone", phone, "email", email))
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
        String email = uniqueEmail("loop");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Loop" + UUID.randomUUID().toString().substring(0, 8)))
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
