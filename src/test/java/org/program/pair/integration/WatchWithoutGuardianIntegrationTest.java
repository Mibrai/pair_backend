package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchEventType;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.WatchEventRepository;
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
 * Armer une veille sans contact d'urgence.
 *
 * <p><b>Ce que ce lot cède, et pourquoi.</b> « Une veille qui ne prévient personne
 * n'est pas une veille » restait vrai du point de vue de l'alerte, et devenait faux
 * le premier soir : sans contact accepté, le bouton était éteint pour quiconque n'en
 * avait pas encore désigné — c'est-à-dire au moment où l'on en a le plus besoin.
 *
 * <p><b>Ce que ce lot ne cède pas</b>, et c'est ce que ces tests gardent : rien ne
 * sort d'une telle veille. Ni alerte, ni lien public, ni surtout l'état
 * {@code ESCALATED}, dont le client tire un bandeau « message d'urgence envoyé » qui
 * serait ici la phrase la plus fausse que l'application puisse écrire.
 */
class WatchWithoutGuardianIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired WatchEventRepository eventRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired WatchReturnLoopJob returnLoopJob;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    // ─── armer ────────────────────────────────────────────────────────────────

    /**
     * {@code guardianId} nul est le <b>seul</b> moyen pour le client de savoir qu'il
     * n'y avait personne à prévenir. Le déduire d'une contrainte d'interface ne
     * marcherait pas : les veilles déjà en base ne la connaissent pas.
     */
    @Test
    void armerSansContact_doitReussir_etServirGuardianIdNul() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);

        Map<?, ?> dto = webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(dto.get("guardianId")).isNull();
        assertThat(dto.get("backupGuardianId")).isNull();
        assertThat(dto.get("state")).isEqualTo("ARMED");
        // Aucune alerte n'a été déposée, et il n'y en aura jamais.
        assertThat(dto.get("alertDelivery")).isEqualTo("NONE");
        assertThat(dto.get("publicToken")).isNull();
    }

    /**
     * Un contact de secours seul ne seconde personne : la branche du secours ne
     * s'ouvre qu'après que le principal a été prévenu sans rien ouvrir. L'accepter
     * armerait une veille dont le seul contact ne serait jamais joint — pire que
     * pas de contact du tout, puisque l'application croirait alors qu'il y en a un.
     */
    @Test
    void unSecoursSansPrincipal_doitEtreRefuse() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID secours = contactAccepte(moi);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                              "backupGuardianId", secours.toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NO_GUARDIAN");
    }

    // ─── l'échéance, et ce qui ne part pas ────────────────────────────────────

    /**
     * <b>Le test central de ce chantier.</b>
     *
     * <p>Les rappels partent — c'est l'essentiel de ce qu'une veille sans contact
     * apporte, et c'est ce qui fait qu'on n'oublie pas de dire qu'on est rentré.
     * Puis, à l'heure où une veille ordinaire escalade, celle-ci se referme :
     * {@code NO_CONTACT}, rien dans l'outbox, aucun jeton public.
     */
    @Test
    void aLecheance_lesRappelsPartent_puisLaVeilleSeReferme() {
        Compte moi = compte();
        UUID watchId = armerSansContact(moi);
        // L'échéance de retour ne concerne que les veilles arrivées : sans arrivée
        // validée, une veille relève de la boucle aller et se referme en
        // NOT_ARRIVED, qui n'envoie rien non plus.
        arriver(moi, watchId);
        reculerEcheance(watchId, 90);

        returnLoopJob.tick();
        returnLoopJob.tick();
        returnLoopJob.tick();
        assertThat(watch(watchId).getRemindersSent()).isEqualTo(3);
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.REMINDING);

        returnLoopJob.tick();
        Watch apres = watch(watchId);
        assertThat(apres.getState()).isEqualTo(WatchState.NO_CONTACT);
        assertThat(apres.getClosedAt()).isNotNull();

        // Rien n'est sorti, et rien ne pourra sortir : le lien public naît à
        // l'alerte, et il n'y a pas eu d'alerte.
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(apres.getPublicToken()).isNull();
        assertThat(eventRepository.existsByWatchIdAndType(watchId, WatchEventType.ESCALATED))
            .isFalse();
        assertThat(eventRepository.existsByWatchIdAndType(watchId, WatchEventType.CLOSED_NO_CONTACT))
            .isTrue();
    }

    /**
     * L'état fait le travail d'un garde-fou, exactement comme {@code NOT_ARRIVED} le
     * fait depuis le 02/09 : {@code NO_CONTACT} sort du champ de vision de la boucle
     * retour. Sans cela, chaque passage rappellerait {@code ensureAlerted} sur une
     * veille sans destinataire — et un jour, quelqu'un retirerait le garde par
     * mégarde en croyant simplifier.
     */
    @Test
    void unefoisReferme_lesPassagesSuivantsNeFontRien() {
        Compte moi = compte();
        UUID watchId = armerSansContact(moi);
        arriver(moi, watchId);
        reculerEcheance(watchId, 90);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick();
        }
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.NO_CONTACT);

        returnLoopJob.tick();
        returnLoopJob.tick();

        assertThat(watch(watchId).getState()).isEqualTo(WatchState.NO_CONTACT);
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
    }

    /**
     * Terminal ne veut pas dire invisible. C'est le seul endroit où la personne
     * apprend que sa soirée s'est refermée sans réponse — personne n'a été prévenu,
     * ce qui est exactement ce qu'elle avait accepté, et elle doit pouvoir le lire.
     */
    @Test
    void uneVeilleReferméeSansContact_resteVisibleUnJour() {
        Compte moi = compte();
        UUID watchId = armerSansContact(moi);
        arriver(moi, watchId);
        reculerEcheance(watchId, 90);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick();
        }

        List<?> actives = webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();

        assertThat(actives).anySatisfy(l -> {
            Map<?, ?> w = (Map<?, ?>) l;
            assertThat(w.get("id")).isEqualTo(watchId.toString());
            assertThat(w.get("state")).isEqualTo("NO_CONTACT");
            assertThat(w.get("closedAt")).isNotNull();
        });
    }

    /**
     * L'état est terminal des deux côtés — Java et base. Les trois énumérations
     * doivent dire la même chose (WatchState.TERMINAUX, la contrainte de
     * vocabulaire, l'index d'unicité) : si l'index d'unicité l'avait manqué, le
     * service autoriserait ce réarmement et la base le refuserait, ce qui rendrait
     * un 500 à quelqu'un qui reprogramme une séance.
     */
    @Test
    void apresUneClotureSansContact_onPeutReamerSurLeMemeCreneau() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID watchId = armerSansContactSur(moi, scheduleId);
        arriver(moi, watchId);
        reculerEcheance(watchId, 90);
        for (int i = 0; i < 4; i++) {
            returnLoopJob.tick();
        }
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.NO_CONTACT);

        webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString()))
            .exchange().expectStatus().isCreated();
    }

    // ─── les gestes qui n'ont plus de sens ────────────────────────────────────

    /**
     * Panic veut dire « prévenez maintenant ». Sans destinataire, il n'y a rien à
     * faire — et le pire serait de rendre 202 à quelqu'un qui croirait alors que
     * quelqu'un a été alerté. Le code nommé permet au client d'éteindre le bouton
     * plutôt que de le laisser mentir.
     */
    @Test
    void panicSurUneVeilleSansContact_doitEtreRefuseEtNomme() {
        Compte moi = compte();
        UUID watchId = armerSansContact(moi);
        arriver(moi, watchId);

        webTestClient.post().uri("/api/watches/{id}/panic", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NO_GUARDIAN");

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.ON_SITE);
    }

    /**
     * <b>Le code de contrainte n'a rien à faire sur une veille sans contact, et les
     * deux clôtures doivent être indiscernables.</b>
     *
     * <p>Son effet entier est « prévenez le proche en silence » : sans proche, il
     * n'en reste rien. Rendre les deux branches identiques est ici plus protecteur
     * que de marquer la contrainte — une marque que rien ne consomme resterait
     * lisible dans le journal, sur l'appareil que la personne contrainte a
     * peut-être à montrer.
     */
    @Test
    void leCodeDeContrainteSansContact_refermeCommeUneCloturNormale() {
        Compte moi = compte();
        UUID watchId = armerSansContact(moi);
        String code = arriverAvecContrainte(moi, watchId, "SESAME");

        webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", "SESAME", "enteredAt", Instant.now().toString()))
            .exchange().expectStatus().isAccepted();

        Watch apres = watch(watchId);
        assertThat(apres.getState()).isEqualTo(WatchState.CLOSED);
        assertThat(apres.getClosedAt()).isNotNull();
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(eventRepository.existsByWatchIdAndType(watchId, WatchEventType.ESCALATED))
            .isFalse();
        assertThat(code).isNotBlank();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private void reculerEcheance(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setDeadlineAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    private void arriver(Compte moi, UUID watchId) {
        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();
    }

    private String arriverAvecContrainte(Compte moi, UUID watchId, String duress) {
        return String.valueOf(webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("duressCode", duress))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
    }

    private UUID armerSansContact(Compte moi) {
        return armerSansContactSur(moi, creerCreneau(moi));
    }

    private UUID armerSansContactSur(Compte moi, UUID scheduleId) {
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
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

    private UUID contactAccepte(Compte owner) {
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "email", "proche-" + suffixe + "@example.org"))
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
        String email = uniqueEmail("sanscontact");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Seule" + UUID.randomUUID().toString().substring(0, 8)))
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
