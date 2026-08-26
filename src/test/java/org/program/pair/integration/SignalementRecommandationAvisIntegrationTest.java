package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les quatre routes d'écriture qui rendaient 500 en production le 26/08/2026 :
 * signaler un utilisateur, signaler un programme, recommander un pair, évaluer
 * un programme.
 *
 * <p>Elles partageaient une seule ligne, répétée dans trois services : un
 * {@code .id(UUID.randomUUID())} posé à la main sur une entité dont l'identifiant
 * est {@code @GeneratedValue}. Un id déjà posé rend {@code save()} non-« new »
 * pour Spring Data, qui appelle {@code merge()} au lieu de {@code persist()} ;
 * Hibernate 7 refuse de fusionner une instance détachée dont la ligne n'existe
 * pas et lève {@code StaleObjectStateException}, que rien n'attrape — donc un
 * {@code 500 INTERNAL_ERROR} sur le chemin nominal, après une validation et des
 * contrôles métier parfaitement fonctionnels.
 *
 * <p>Chaque test va jusqu'à la route de <b>lecture</b> : un {@code 2xx} qui
 * n'écrit rien est exactement le symptôme qu'on cherche à exclure. C'est aussi
 * ce que le client demandait comme preuve du correctif.
 *
 * <p>Chaque méthode monte son propre décor avec des comptes neufs : rien ne vide
 * la base entre deux méthodes, et « déjà signalé » / « déjà recommandé » sont des
 * refus légitimes qui feraient échouer la deuxième méthode exécutée.
 */
class SignalementRecommandationAvisIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void signalerUnUtilisateur_ecritLeSignalement_etIlApparaitDansMesSignalements() {
        Decor d = monterLeDecor("signal-user");

        webTestClient.post()
            .uri("/api/reports")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"reportedEntityType":"USER","reportedEntityId":"%s","reason":"SPAM",
                 "description":"No additional details provided by the reporter."}
                """.formatted(d.userB))
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.id").isNotEmpty()
            .jsonPath("$.status").isEqualTo("PENDING");

        webTestClient.get()
            .uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1)
            .jsonPath("$.content[0].reportedEntityType").isEqualTo("USER")
            .jsonPath("$.content[0].reportedEntityId").isEqualTo(d.userB.toString())
            .jsonPath("$.content[0].reason").isEqualTo("SPAM");
    }

    @Test
    void signalerUnProgramme_ecritLeSignalement_etIlApparaitDansMesSignalements() {
        Decor d = monterLeDecor("signal-program");

        webTestClient.post()
            .uri("/api/programs/{programId}/report", d.programId)
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"reason":"INAPPROPRIATE_CONTENT","description":"Contenu manifestement hors sujet."}
                """)
            .exchange()
            .expectStatus().isCreated();

        webTestClient.get()
            .uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1)
            .jsonPath("$.content[0].reportedEntityType").isEqualTo("PROGRAM")
            .jsonPath("$.content[0].reportedEntityId").isEqualTo(d.programId.toString());
    }

    @Test
    void recommanderUnPair_ecritLaRecommandation_etCanRecommendBascule() {
        Decor d = monterLeDecor("reco");

        webTestClient.get()
            .uri("/api/recommendations/can-recommend/{userId}", d.userB)
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Boolean.class).isEqualTo(true);

        webTestClient.post()
            .uri("/api/recommendations")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"recommendedId\":\"%s\"}".formatted(d.userB))
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.id").isNotEmpty()
            .jsonPath("$.recommendedId").isEqualTo(d.userB.toString())
            .jsonPath("$.recommenderId").isEqualTo(d.userA.toString());

        webTestClient.get()
            .uri("/api/recommendations/given")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1)
            .jsonPath("$.content[0].recommendedId").isEqualTo(d.userB.toString());

        webTestClient.get()
            .uri("/api/recommendations/can-recommend/{userId}", d.userB)
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Boolean.class).isEqualTo(false);
    }

    @Test
    void evaluerUnProgramme_ecritLAvis_etIlApparaitDansMesAvis() {
        Decor d = monterLeDecor("avis");

        webTestClient.post()
            .uri("/api/reviews")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"programId":"%s","score":5.0,
                 "comment":"Séance très bien menée, ambiance accueillante et rythme adapté."}
                """.formatted(d.programId))
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.id").isNotEmpty()
            .jsonPath("$.programId").isEqualTo(d.programId.toString());

        webTestClient.get()
            .uri("/api/reviews/me")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1)
            .jsonPath("$.content[0].programId").isEqualTo(d.programId.toString());
    }

    @Test
    void signalerUneCibleInexistante_rend404_etNonUneErreurServeur() {
        Decor d = monterLeDecor("cible-absente");

        // Les quatre types de l'énumération, aucun n'était résolu auparavant :
        // un identifiant bien formé mais orphelin allait jusqu'à l'insertion.
        for (String type : new String[] {"USER", "PROGRAM", "MESSAGE", "REVIEW"}) {
            webTestClient.post()
                .uri("/api/reports")
                .headers(h -> h.setBearerAuth(d.tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                    {"reportedEntityType":"%s","reportedEntityId":"%s","reason":"SPAM",
                     "description":"No additional details provided by the reporter."}
                    """.formatted(type, UUID.randomUUID()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
        }

        // Un 404 ne doit rien avoir écrit au passage.
        webTestClient.get()
            .uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(0);
    }

    @Test
    void signalerDeuxFoisLeMemeElement_rend409Nomme() {
        Decor d = monterLeDecor("signal-doublon");
        String corps = """
            {"reportedEntityType":"USER","reportedEntityId":"%s","reason":"SPAM",
             "description":"No additional details provided by the reporter."}
            """.formatted(d.userB);

        webTestClient.post()
            .uri("/api/reports")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isCreated();

        webTestClient.post()
            .uri("/api/reports")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REPORT_ALREADY_SUBMITTED");

        // Le premier signalement tient toujours, et il n'y en a pas deux.
        webTestClient.get()
            .uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1);
    }

    @Test
    void recommanderDeuxFoisLaMemePersonne_rend409Nomme() {
        Decor d = monterLeDecor("reco-doublon");
        String corps = "{\"recommendedId\":\"%s\"}".formatted(d.userB);

        webTestClient.post()
            .uri("/api/recommendations")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isCreated();

        webTestClient.post()
            .uri("/api/recommendations")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RECOMMENDATION_ALREADY_GIVEN");

        webTestClient.get()
            .uri("/api/recommendations/given")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page.totalElements").isEqualTo(1);
    }

    /**
     * Le refus de droit, lui, reste un {@code 422} : c'est la distinction que le
     * client demandait de nommer. Sans preuve d'interaction, recommander n'est
     * pas « déjà fait », c'est « pas permis ».
     */
    @Test
    void recommanderSansPreuveDInteraction_resteEn422() {
        Decor d = monterLeDecor("reco-sans-preuve");
        String etranger = uniqueEmail("reco-sans-preuve-c");
        inscrireEtConnecter(etranger);
        UUID userC = userRepository.findByEmail(etranger).orElseThrow().getId();

        webTestClient.post()
            .uri("/api/recommendations")
            .headers(h -> h.setBearerAuth(d.tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"recommendedId\":\"%s\"}".formatted(userC))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    /**
     * Deux comptes neufs, un programme tenu par B, et une présence confirmée par
     * les deux sur le même créneau passé — la preuve d'interaction
     * {@code SHARED_ATTENDANCE} qu'exigent la recommandation et l'avis.
     *
     * <p>Les présences sont écrites directement : {@code POST /api/attendances}
     * refuse quiconque n'était pas inscrit au créneau, et l'inscription n'est pas
     * le sujet de ces tests.
     */
    private Decor monterLeDecor(String prefixe) {
        String emailA = uniqueEmail(prefixe + "-a");
        String emailB = uniqueEmail(prefixe + "-b");
        String tokenA = inscrireEtConnecter(emailA);
        inscrireEtConnecter(emailB);

        User a = userRepository.findByEmail(emailA).orElseThrow();
        User b = userRepository.findByEmail(emailB).orElseThrow();

        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(b).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Séance tenue par B")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant debut = Instant.now().minus(3, ChronoUnit.HOURS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());

        presenceConfirmee(schedule, a, debut);
        presenceConfirmee(schedule, b, debut);

        return new Decor(tokenA, a.getId(), b.getId(), program.getId());
    }

    private void presenceConfirmee(Schedule schedule, User user, Instant debut) {
        Attendance attendance = new Attendance();
        attendance.setSchedule(schedule);
        attendance.setUser(user);
        attendance.setWasPresent(true);
        attendance.setAttendedAt(debut);
        attendance.setConfirmedAt(Instant.now());
        attendanceRepository.save(attendance);
    }

    private String inscrireEtConnecter(String email) {
        org.program.pair.domain.auth.dto.RegisterRequest registerReq =
            new org.program.pair.domain.auth.dto.RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        org.program.pair.domain.auth.dto.LoginRequest loginReq =
            new org.program.pair.domain.auth.dto.LoginRequest(email, "Password123!");
        org.program.pair.domain.auth.dto.AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(org.program.pair.domain.auth.dto.AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }

    private record Decor(String tokenA, UUID userA, UUID userB, UUID programId) {}
}
