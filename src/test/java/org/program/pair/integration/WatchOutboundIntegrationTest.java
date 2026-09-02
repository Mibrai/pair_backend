package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.domain.watch.jobs.WatchOutboundJob;
import org.program.pair.domain.watch.jobs.WatchReturnLoopJob;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.IncidentRepository;
import org.program.pair.repository.NotificationRepository;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La boucle aller : les demandes « tu y es ? », le « je suis en chemin » qui les
 * repousse, l'abandon, et le « perdu en chemin » qui journalise un incident sans
 * jamais compter d'absence.
 *
 * <p>Le test qui porte le garde-fou du §6 est
 * {@link #perduEnChemin_journaliseUnIncident_jamaisUneAbsence()} : un incident de
 * sécurité ne doit pas se muer en reproche, sans quoi la personne désarme la
 * veille la fois d'après.
 */
class WatchOutboundIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WatchOutboundJob outboundJob;
    @Autowired WatchReturnLoopJob returnLoopJob;
    @Autowired OutboxService outboxService;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Test
    void troisDemandesSansArrivee_puisPerduEnChemin() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0612345678", "aller@example.org");
        reculerBaseAller(watchId, 60); // 60 min après le début : tout est dû.

        outboundJob.tick();
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.EN_ROUTE);
        assertThat(watch(watchId).getArrivalPromptsSent()).isEqualTo(1);
        // Pas encore d'alerte : l'étiquette ne se pose qu'à la troisième demande.
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();

        outboundJob.tick();
        outboundJob.tick();
        assertThat(watch(watchId).getArrivalPromptsSent()).isEqualTo(3);
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();

        // Quatrième passage : perdu en chemin. La veille se referme, et personne
        // n'est prévenu — décision du 02/09. Aucun jeton public non plus : le lien
        // naît à l'alerte, et il n'y a pas d'alerte.
        outboundJob.tick();
        Watch apres = watch(watchId);
        assertThat(apres.getState()).isEqualTo(WatchState.NOT_ARRIVED);
        assertThat(apres.getClosedAt()).isNotNull();
        assertThat(apres.getPublicToken()).isNull();
        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
    }

    /**
     * Le défaut que le retrait du message ⑤ aurait ouvert, et le seul qui comptait
     * vraiment dans ce lot.
     *
     * <p>Tant que la non-arrivée posait {@code ESCALATED}, la <b>boucle retour</b>
     * la reprenait à son échéance — elle balaie cet état — et {@code ensureAlerted}
     * envoyait l'alerte retour ② au contact, une heure après le T+45. Rien ne
     * l'empêchait sinon l'outbox non vide, c'est-à-dire un effet de bord du message
     * ⑤ lui-même. Le retirer sans changer d'état aurait donc fait partir ② à sa
     * place : « n'est pas rentrée », pour quelqu'un qui n'est jamais parti.
     *
     * <p>Ce qui protège maintenant est l'état : {@code NOT_ARRIVED} n'est pas dans
     * le champ de vision de la boucle retour.
     */
    @Test
    void nonArrivee_neFaitPasPartirLalerteRetourALecheance() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0677001122", "backdoor@example.org");
        reculerBaseAller(watchId, 60);
        for (int i = 0; i < 4; i++) {
            outboundJob.tick();
        }
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.NOT_ARRIVED);

        // L'échéance de retour est franchie, et largement : la boucle retour aurait
        // tout ce qu'il lui faut pour escalader.
        reculerEcheance(watchId, 90);
        returnLoopJob.tick();
        returnLoopJob.tick();

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.NOT_ARRIVED);
        assertThat(watch(watchId).getPublicToken()).isNull();
    }

    /**
     * Le même défaut, dans la forme où il était déjà en production avant ce lot.
     *
     * <p>Quand le contact d'urgence a un compte meetDo, le message ⑤ prenait la
     * branche in-app et ne déposait <b>rien</b> dans l'outbox. Le garde-fou
     * d'{@code ensureAlerted} — « un message a-t-il déjà été déposé ? » — ne tenait
     * donc pas, et l'alerte retour ② partait à l'échéance : notification in-app plus
     * e-mail « Alerte retour », pour une personne jamais arrivée. Aucun test ne
     * passait par là : la boucle aller n'était armée qu'avec des contacts externes,
     * et s'arrêtait avant l'échéance de retour.
     */
    @Test
    void nonArrivee_avecContactMembre_neFaitPartirAucuneAlerte() {
        Compte moi = compte();
        Compte proche = compte();
        UUID watchId = armerAvecContactMembre(moi, proche);
        reculerBaseAller(watchId, 60);
        for (int i = 0; i < 4; i++) {
            outboundJob.tick();
        }
        assertThat(watch(watchId).getState()).isEqualTo(WatchState.NOT_ARRIVED);

        reculerEcheance(watchId, 90);
        returnLoopJob.tick();
        returnLoopJob.tick();

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(notificationRepository.findAll()).noneSatisfy(n ->
            assertThat(n.getType()).isEqualTo(NotificationType.WATCH_GUARDIAN_ALERT));
    }

    /**
     * Une non-arrivée est terminale, mais elle reste <b>listée</b> 24 h.
     *
     * <p>Sans cela, après T+45 l'organisateur reçoit une notification et la personne
     * concernée n'en reçoit aucune : sa soirée est classée perdue en chemin, un
     * incident est journalisé à son nom, et rien dans l'app ne le lui dit. Cette
     * liste est le seul endroit où elle l'apprend.
     */
    @Test
    void nonArrivee_resteListeeParLesVeillesActivesPendantVingtQuatreHeures() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0677334455", "visible@example.org");
        reculerBaseAller(watchId, 60);
        for (int i = 0; i < 4; i++) {
            outboundJob.tick();
        }

        webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$[?(@.id=='" + watchId + "')].state").isEqualTo("NOT_ARRIVED")
            // Aucun message n'est parti : le bandeau global du client lit ce champ,
            // et un BOUNCED y voudrait dire « le proche n'a pas été joint ».
            .jsonPath("$[?(@.id=='" + watchId + "')].alertDelivery").isEqualTo("NONE");
        // Aucun jeton public : le lien naît à l'alerte, et il n'y a pas d'alerte.
        assertThat(watch(watchId).getPublicToken()).isNull();

        // Passé 24 h, elle ne vit plus que dans le journal, qui est sa place.
        reculerCloture(watchId, 25);
        webTestClient.get().uri("/api/watches/active")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[?(@.id=='" + watchId + "')]").doesNotExist();

        webTestClient.get().uri("/api/watches/history")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[?(@.id=='" + watchId + "')].state").isEqualTo("NOT_ARRIVED");
    }

    /**
     * Rester listée ne veut pas dire rester vivante : la veille est close, et elle
     * ne bloque pas un nouvel armement sur le même créneau.
     *
     * <p>C'est ce que garantit le fait que {@code NOT_ARRIVED} reste dans
     * {@code TERMINAUX} — la visibilité est servie par une requête à part, jamais en
     * assouplissant cet ensemble.
     */
    @Test
    void nonArrivee_neBloquePasUnNouvelArmementSurLeMemeCreneau() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi, "0677667788", "rearmer@example.org");
        UUID premiere = armerSur(moi, scheduleId, guardianId);
        reculerBaseAller(premiere, 60);
        for (int i = 0; i < 4; i++) {
            outboundJob.tick();
        }
        assertThat(watch(premiere).getState()).isEqualTo(WatchState.NOT_ARRIVED);

        UUID seconde = armerSur(moi, scheduleId, guardianId);
        assertThat(seconde).isNotEqualTo(premiere);
    }

    /**
     * La porte de secours des veilles déjà bloquées en production.
     *
     * <p>Une veille escaladée sans arrivée validée n'avait <b>aucune sortie</b> :
     * l'arrivée est refusée, le snooze et l'interruption supposent d'être sur place,
     * le désarmement ne vaut qu'en {@code ARMED}, et la clôture réclame un code qui
     * n'a jamais existé. Elle restait ouverte indéfiniment et bloquait l'armement
     * d'une nouvelle veille sur le même créneau. {@code NOT_ARRIVED} empêche qu'il
     * s'en crée d'autres ; il ne libère pas celles qui y sont déjà.
     */
    @Test
    void abandon_ouvreLaSortieDuneVeilleEscaladeeSansArrivee() {
        Compte moi = compte();
        UUID scheduleId = creerCreneau(moi);
        UUID guardianId = contactAccepte(moi, "0677223344", "bloquee@example.org");
        UUID watchId = armerSur(moi, scheduleId, guardianId);
        escaladeHeritee(watchId);

        webTestClient.post().uri("/api/watches/{id}/abandon", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("NOT_ARRIVED");

        // Refermée en NOT_ARRIVED et jamais en CLOSED : ces veilles ont un jeton
        // public distribué, et CLOSED ferait dire « Bien rentrée » à la page du
        // proche de quelqu'un qui n'est jamais arrivé.
        assertThat(watch(watchId).getClosedAt()).isNotNull();
        // Et le créneau se réarme : c'était ce que le blocage interdisait.
        assertThat(armerSur(moi, scheduleId, guardianId)).isNotEqualTo(watchId);
    }

    /**
     * Ces veilles-là ont fait partir le message ⑤ : un proche a été prévenu que
     * quelqu'un n'était pas arrivé. Refermer sans rien lui dire le laisserait sur
     * la dernière chose qu'on lui a dite. C'est la règle que le module applique
     * déjà à la clôture par code.
     */
    @Test
    void abandon_dUneEscaladeHeritee_faitPartirLaLevee() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0677445566", "levee@example.org");
        // L'alerte héritée, telle que l'ancienne branche aller l'avait déposée.
        outboxService.enqueueEmail("levee@example.org", "Non-arrivée — meetDo",
            "<p>ancienne alerte</p>", 5, watchId);
        escaladeHeritee(watchId);

        webTestClient.post().uri("/api/watches/{id}/abandon", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();

        assertThat(outboxRepository.findByWatchId(watchId))
            .anySatisfy(m -> assertThat(m.getSubject()).contains("Fausse alerte"));
    }

    /** Une veille neuve, sans alerte partie, ne fait partir aucune levée. */
    @Test
    void abandon_sansAlerteHeritee_neFaitPartirAucunMessage() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0677556677", "sanslevee@example.org");
        escaladeHeritee(watchId);

        webTestClient.post().uri("/api/watches/{id}/abandon", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
    }

    /**
     * « Prévenir maintenant » suppose qu'on soit sur place.
     *
     * <p>L'app ne propose plus le geste avant l'arrivée, mais le refus serveur n'est
     * pas une redondance : une app plus ancienne, un rejeu de file hors ligne ou un
     * bouton d'écran verrouillé oublié suffiraient à faire partir le message que la
     * décision du 02/09 retire.
     */
    @Test
    void panic_estRefuseTantQueLarriveeNestPasValidee() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0677889900", "panic@example.org");

        webTestClient.post().uri("/api/watches/{id}/panic", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NOT_ON_SITE");

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(watch(watchId).getState()).isNotEqualTo(WatchState.ESCALATED);
    }

    @Test
    void perduEnChemin_journaliseUnIncident_jamaisUneAbsence() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0698112233", "incident@example.org");
        reculerBaseAller(watchId, 60);
        for (int i = 0; i < 4; i++) {
            outboundJob.tick();
        }

        // Un incident TRANSIT est écrit...
        assertThat(incidentRepository.existsByWatchId(watchId)).isTrue();
        // ...et AUCUNE ligne Attendance : un perdu en chemin ne pèse pas contre la fiabilité.
        assertThat(attendanceRepository.existsByScheduleIdAndUserId(
            watch(watchId).getScheduleId(), moi.id())).isFalse();
    }

    @Test
    void jeSuisEnChemin_repousseLaRelanceDeQuinzeMinutes() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0611998877", "enchemin@example.org");
        Instant baseAvant = watch(watchId).getOutboundBaseAt();

        webTestClient.post().uri("/api/watches/{id}/still-coming", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("EN_ROUTE");

        assertThat(watch(watchId).getOutboundBaseAt())
            .isEqualTo(baseAvant.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void jeNyVaisPas_fermeSansMessageNiAbsence() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0655443322", "abandon@example.org");

        webTestClient.post().uri("/api/watches/{id}/abandon", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("CLOSED");

        assertThat(outboxRepository.findByWatchId(watchId)).isEmpty();
        assertThat(attendanceRepository.existsByScheduleIdAndUserId(
            watch(watchId).getScheduleId(), moi.id())).isFalse();
    }

    @Test
    void cesGestes_neValentQueSurLeTrajetAller() {
        Compte moi = compte();
        UUID watchId = armer(moi, "0644332211", "trajet@example.org");
        // On valide l'arrivée : la veille n'est plus sur le trajet aller.
        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk();

        webTestClient.post().uri("/api/watches/{id}/still-coming", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NOT_OUTBOUND");
    }

    // ------------------------------------------------------------------ outils

    private Watch watch(UUID watchId) {
        return watchRepository.findById(watchId).orElseThrow();
    }

    private void reculerBaseAller(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setOutboundBaseAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /** Amène l'échéance de retour dans le passé, pour donner sa chance à la boucle retour. */
    private void reculerEcheance(UUID watchId, long minutes) {
        Watch w = watch(watchId);
        w.setDeadlineAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        watchRepository.saveAndFlush(w);
    }

    /**
     * Remet une veille dans l'impasse que l'ancienne boucle aller produisait :
     * {@code ESCALATED}, sans arrivée validée, avec son jeton public déjà distribué.
     */
    private void escaladeHeritee(UUID watchId) {
        Watch w = watch(watchId);
        w.setState(WatchState.ESCALATED);
        w.setArrivalConfirmedAt(null);
        w.setPublicToken("jetonHerite" + watchId.toString().substring(0, 8));
        watchRepository.saveAndFlush(w);
    }

    /** Vieillit la clôture, pour sortir de la fenêtre de visibilité de 24 h. */
    private void reculerCloture(UUID watchId, long heures) {
        Watch w = watch(watchId);
        w.setClosedAt(Instant.now().minus(heures, ChronoUnit.HOURS));
        watchRepository.saveAndFlush(w);
    }

    private UUID armer(Compte owner, String phone, String email) {
        return armerSur(owner, creerCreneau(owner), contactAccepte(owner, phone, email));
    }

    /** Une veille dont le contact d'urgence est un membre meetDo, pas un contact externe. */
    private UUID armerAvecContactMembre(Compte owner, Compte proche) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("memberId", proche.id().toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return armerSur(owner, creerCreneau(owner), guardianId);
    }

    private UUID armerSur(Compte owner, UUID scheduleId, UUID guardianId) {
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
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
        String email = uniqueEmail("aller");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Aller" + UUID.randomUUID().toString().substring(0, 8)))
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
