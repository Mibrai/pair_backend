package org.program.pair.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande 3(c) de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md : un refus
 * métier doit porter un code stable qui nomme le refus, et non la catégorie
 * technique de l'exception. Avant ce changement, « vous avez déjà rejoint ce
 * créneau » et « ce créneau est complet » sortaient tous deux avec le même code
 * (BUSINESS_RULE_VIOLATION / VALIDATION_ERROR) : seul le message français les
 * distinguait, donc rien d'exploitable par un client à traduire.
 *
 * <p>Le code est le contrat ; le message ne l'est pas. Ces tests n'assertent
 * jamais sur le message.
 */
class BusinessErrorCodeIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository slotParticipationRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double LAT = 48.8566;
    private static final double LNG = 2.3522;

    private static final String HOST_EMAIL = "error-code-host@pair.app";
    private static final String JOINER_EMAIL = "error-code-joiner@pair.app";

    private static boolean accountsCreated = false;
    private static String hostToken;
    private static String joinerToken;

    private User host;

    @BeforeEach
    void setUp() {
        if (!accountsCreated) {
            hostToken = registerAndLogin(HOST_EMAIL);
            joinerToken = registerAndLogin(JOINER_EMAIL);
            accountsCreated = true;
        }
        host = userRepository.findByEmail(HOST_EMAIL).orElseThrow();
    }

    @Test
    void rejoindreDeuxFoisLeMemeCreneau_doitRenvoyer422_SLOT_ALREADY_JOINED() {
        UUID slotId = createSlot("Cours code erreur — double join", 8);

        join(joinerToken, slotId).expectStatus().isCreated();

        join(joinerToken, slotId)
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SLOT_ALREADY_JOINED")
            .jsonPath("$.message").isNotEmpty()
            .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void rejoindreSonPropreCreneau_doitRenvoyerUnCodeDistinctDuDoubleJoin() {
        UUID slotId = createSlot("Cours code erreur — propre créneau", 8);

        join(hostToken, slotId)
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SLOT_OWN_SLOT");
    }

    @Test
    void creneauComplet_doitRenvoyerUnCodeDistinctDuDoubleJoin() {
        // Capacité 1, consommée par une participation insérée directement en base :
        // maxParticipants porte un @Min(1), donc un créneau de capacité 0 est
        // impossible, et l'hôte ne peut pas remplir sa propre place par l'API.
        UUID slotId = createSlot("Cours code erreur — complet", 1);
        fillOneSeat(slotId, host);

        join(joinerToken, slotId)
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SLOT_FULL");
    }

    @Test
    void creneauFermeAuxPartenaires_doitRenvoyerSonPropreCode() {
        UUID slotId = createSlot("Cours code erreur — fermé", 8, false, SlotStatus.OPEN);

        join(joinerToken, slotId)
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SLOT_NOT_OPEN_TO_PARTNERS");
    }

    @Test
    void leCode_doitEtreIdentiqueQuelleQueSoitLaLangueDemandee() {
        UUID slotId = createSlot("Cours code erreur — langue", 8);
        join(joinerToken, slotId).expectStatus().isCreated();

        // Accept-Language n'est pas encore honoré (demande 3 a/b), mais le code
        // ne doit jamais dépendre de la langue — ni maintenant, ni après.
        for (String language : new String[]{"fr", "en", "de", "it"}) {
            webTestClient.post()
                .uri("/api/slots/{id}/join", slotId)
                .headers(h -> {
                    h.setBearerAuth(joinerToken);
                    h.set("Accept-Language", language);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new JoinSlotRequest(null))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLOT_ALREADY_JOINED");
        }
    }

    @Test
    void uneRessourceIntrouvable_doitGarderSonCodeGeneriqueHistorique() {
        // Non-régression : les refus non encore nommés gardent exactement le
        // corps d'erreur qu'ils produisaient avant l'introduction d'ErrorCode.
        join(joinerToken, UUID.randomUUID())
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    // — helpers —

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec join(
            String token, UUID slotId) {
        return webTestClient.post()
            .uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new JoinSlotRequest(null))
            .exchange();
    }

    private void fillOneSeat(UUID slotId, User occupant) {
        SlotParticipation participation = new SlotParticipation();
        participation.setSchedule(scheduleRepository.findById(slotId).orElseThrow());
        participation.setUser(occupant);
        participation.setStatus(ParticipationStatus.CONFIRMED);
        slotParticipationRepository.save(participation);
    }

    private UUID createSlot(String title, Integer maxParticipants) {
        return createSlot(title, maxParticipants, true, SlotStatus.OPEN);
    }

    private UUID createSlot(String title, Integer maxParticipants,
                             boolean openToPartners, SlotStatus status) {
        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository
            .findByUserIdAndActivityId(host.getId(), activity.getId())
            .orElseGet(() -> userActivityRepository.save(
                UserActivity.builder().user(host).activity(activity).build()));

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test codes d'erreur")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue des Codes")
            .showExactAddress(true)
            .location(geometryFactory.createPoint(new Coordinate(LNG, LAT)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(1, ChronoUnit.HOURS))
            .maxParticipants(maxParticipants)
            .isOpenToPartners(openToPartners)
            .status(status)
            .build());

        return schedule.getId();
    }

    private String registerAndLogin(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
