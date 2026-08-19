package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot C1 — liste d'attente.
 *
 * <p>Le lot dont la spécification demande explicitement de prouver la
 * correction sous désistements concurrents. Le dépôt n'avait aucun test de
 * concurrence ; celui du bas de ce fichier est le premier.
 */
class SlotWaitlistIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — la file —

    @Test
    void unCreneauComplet_doitAccepterLaMiseEnAttente() {
        // joinSlot refuse un créneau FULL avant même de compter les places :
        // sans autorisation explicite, cette route rejetterait exactement les
        // créneaux pour lesquels elle existe.
        Ctx ctx = fullSlot();

        SlotFeedItemDto slot = joinWaitlist(ctx.candidate, ctx.slotId);

        assertThat(slot.myParticipationStatus()).isEqualTo("WAITLISTED");
        assertThat(slot.myWaitlistPosition()).isEqualTo(1);
    }

    @Test
    void lesRangs_doiventSuivreLOrdreDArrivee() {
        Ctx ctx = fullSlot();
        String second = registerAndLogin();

        assertThat(joinWaitlist(ctx.candidate, ctx.slotId).myWaitlistPosition()).isEqualTo(1);
        assertThat(joinWaitlist(second, ctx.slotId).myWaitlistPosition()).isEqualTo(2);
    }

    @Test
    void quitterLaFile_doitRemonterLesSuivants() {
        Ctx ctx = fullSlot();
        String second = registerAndLogin();
        joinWaitlist(ctx.candidate, ctx.slotId);
        joinWaitlist(second, ctx.slotId);

        webTestClient.delete().uri("/api/slots/{id}/waitlist", ctx.slotId)
            .headers(h -> h.setBearerAuth(ctx.candidate))
            .exchange().expectStatus().isNoContent();

        // Sans recompactage, le second resterait « 2e » d'une file d'une personne.
        assertThat(slotFor(second, ctx.slotId).myWaitlistPosition()).isEqualTo(1);
    }

    @Test
    void laFile_neCompteJamaisDansLaCapacite() {
        // C'est l'invariant qui rend la promotion possible : élargir le décompte
        // des places à la file remplirait le créneau avec sa propre attente.
        Ctx ctx = fullSlot();
        joinWaitlist(ctx.candidate, ctx.slotId);

        Integer count = jdbcTemplate.queryForObject(
            "SELECT participant_count FROM schedules WHERE id = ?", Integer.class, ctx.slotId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void laFile_neDoitPasApparaitreDansLesParticipants() {
        Ctx ctx = fullSlot();
        joinWaitlist(ctx.candidate, ctx.slotId);

        List<Map> participants = webTestClient.get()
            .uri("/api/slots/{id}/participants", ctx.slotId)
            .headers(h -> h.setBearerAuth(ctx.host))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(participants).noneMatch(p -> "WAITLISTED".equals(p.get("status")));
    }

    @Test
    void laFile_nEstVisibleQueDeLOrganisateur() {
        Ctx ctx = fullSlot();
        joinWaitlist(ctx.candidate, ctx.slotId);

        webTestClient.get().uri("/api/slots/{id}/waitlist", ctx.slotId)
            .headers(h -> h.setBearerAuth(ctx.host))
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).hasSize(1);

        // 404 et non 403 : un refus nommé confirmerait l'existence du créneau.
        webTestClient.get().uri("/api/slots/{id}/waitlist", ctx.slotId)
            .headers(h -> h.setBearerAuth(ctx.candidate))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void unCreneauOuJAttends_doitResterDansMesCreneaux() {
        Ctx ctx = fullSlot();
        joinWaitlist(ctx.candidate, ctx.slotId);

        List<SlotFeedItemDto> mine = webTestClient.get()
            .uri(b -> b.path("/api/slots/mine").build())
            .headers(h -> h.setBearerAuth(ctx.candidate))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();

        assertThat(mine).extracting(SlotFeedItemDto::scheduleId).contains(ctx.slotId);
    }

    // — la promotion —

    @Test
    void unDesistement_doitPromouvoirLePremierDeLaFile() {
        Ctx ctx = fullSlot();
        String second = registerAndLogin();
        joinWaitlist(ctx.candidate, ctx.slotId);
        joinWaitlist(second, ctx.slotId);

        leaveSlot(ctx.occupant, ctx.slotId);

        assertThat(slotFor(ctx.candidate, ctx.slotId).myParticipationStatus()).isEqualTo("CONFIRMED");
        // Le second reste en attente, et remonte au premier rang.
        SlotFeedItemDto forSecond = slotFor(second, ctx.slotId);
        assertThat(forSecond.myParticipationStatus()).isEqualTo("WAITLISTED");
        assertThat(forSecond.myWaitlistPosition()).isEqualTo(1);
    }

    @Test
    void lePromu_doitEtreNotifie() throws Exception {
        Ctx ctx = fullSlot();
        joinWaitlist(ctx.candidate, ctx.slotId);

        leaveSlot(ctx.occupant, ctx.slotId);

        // notify est @Async : la requête HTTP rend la main avant l'écriture de la
        // ligne. Interroger la base aussitôt lit parfois zéro — un test qui passe
        // ou échoue selon la charge, ce que le lot 0 a passé du temps à retirer.
        UUID promotedId = userId(ctx.candidate);
        long notifications = 0;
        for (int attempt = 0; attempt < 50 && notifications == 0; attempt++) {
            notifications = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE user_id = ? AND type = 'WAITLIST_PROMOTED'
                """, Long.class, promotedId);
            if (notifications == 0) {
                Thread.sleep(100);
            }
        }
        assertThat(notifications).isEqualTo(1);
    }

    @Test
    void unDesistementDeLaFile_neDoitPromouvoirPersonne() {
        // Seule une place réellement libérée fait avancer la file.
        Ctx ctx = fullSlot();
        String second = registerAndLogin();
        joinWaitlist(ctx.candidate, ctx.slotId);
        joinWaitlist(second, ctx.slotId);

        webTestClient.delete().uri("/api/slots/{id}/waitlist", ctx.slotId)
            .headers(h -> h.setBearerAuth(ctx.candidate))
            .exchange().expectStatus().isNoContent();

        assertThat(slotFor(second, ctx.slotId).myParticipationStatus()).isEqualTo("WAITLISTED");
    }

    // — concurrence : l'exigence explicite de la spécification —

    @Test
    void deuxDesistementsSimultanes_neDoiventPromouvoirQueDeuxPersonnesDistinctes() throws Exception {
        // Le premier test de concurrence du dépôt. Il ne prouve quelque chose que
        // sur une base réelle : le verrou pessimiste n'existe pas sur des mocks.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 2);

        String occupantA = registerAndLogin();
        String occupantB = registerAndLogin();
        join(occupantA, slotId);
        join(occupantB, slotId);   // créneau plein

        // Le limiteur plafonne les inscriptions à cinq par contexte, et ce test
        // en demande six. Le remettre à zéro ici est plus honnête que de réduire
        // le scénario : c'est avec trois personnes en file que « deux
        // désistements ne doivent pas sauter un rang » veut dire quelque chose.
        resetRateLimiter();

        String waiting1 = registerAndLogin();
        String waiting2 = registerAndLogin();
        String waiting3 = registerAndLogin();
        joinWaitlist(waiting1, slotId);
        joinWaitlist(waiting2, slotId);
        joinWaitlist(waiting3, slotId);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (String occupant : List.of(occupantA, occupantB)) {
            pool.submit(() -> {
                start.await();
                leaveSlot(occupant, slotId);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Deux places libérées, deux promotions — jamais la même personne deux
        // fois, jamais un rang sauté.
        List<Map<String, Object>> promoted = jdbcTemplate.queryForList("""
            SELECT user_id FROM slot_participations
            WHERE schedule_id = ? AND status = 'CONFIRMED' AND promoted_at IS NOT NULL
            """, slotId);

        assertThat(promoted).hasSize(2);
        assertThat(promoted).extracting(r -> r.get("user_id")).doesNotHaveDuplicates();

        // Le troisième attend toujours, et il est désormais premier.
        assertThat(slotFor(waiting3, slotId).myWaitlistPosition()).isEqualTo(1);
    }

    // — helpers —

    private record Ctx(String host, String occupant, String candidate, UUID slotId) {}

    /** Un créneau d'une place, déjà occupée. */
    private Ctx fullSlot() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 1);
        String occupant = registerAndLogin();
        join(occupant, slotId);
        return new Ctx(host, occupant, registerAndLogin(), slotId);
    }

    private void join(String token, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private void leaveSlot(String token, UUID slotId) {
        webTestClient.delete().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isNoContent();
    }

    private SlotFeedItemDto joinWaitlist(String token, UUID slotId) {
        return webTestClient.post().uri("/api/slots/{id}/waitlist", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
    }

    private SlotFeedItemDto slotFor(String token, UUID slotId) {
        return webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
    }

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private UUID publishSlot(String token, int maxParticipants) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(4, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", maxParticipants, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("waitlist");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Participant"))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
