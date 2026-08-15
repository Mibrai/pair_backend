package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.jobs.RecurringSlotRolloverJob;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les cartes-souvenirs sur un créneau <b>récurrent</b>, et les trois lectures
 * contextuelles demandées par
 * {@code docs/specs/PROMPT_BACKEND_RECAP_VISUALISATION_2026-08.md}.
 *
 * <p>Le décor de tous ces tests est celui que le client ne pouvait pas
 * observer : un créneau hebdomadaire dont une séance vient de se terminer et
 * que {@code RecurringSlotRolloverJob} a déjà avancé à la semaine suivante.
 * C'est dans cet état — le seul que connaissent les programmes de seed, tous
 * récurrents — que la carte se datait du futur, que la fenêtre de sept jours
 * ne se refermait jamais, et qu'une deuxième présence était refusée.
 *
 * <p>Les trois nouvelles routes sont interrogées en HTTP plutôt que par le
 * service : leurs requêtes sont des JPQL écrites à la main, et seule une vraie
 * base dit si elles filtrent ce qu'elles prétendent filtrer.
 */
class RecapOccurrenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired RecurringSlotRolloverJob rolloverJob;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private static final ParameterizedTypeReference<List<SlotRecapDto>> RECAP_LIST =
        new ParameterizedTypeReference<>() {};

    @Test
    void laCarteDUneSeanceRecurrente_porteLaDateVecue_etNonCelleDeLaSeanceSuivante() {
        Fixture f = weeklySlotWhoseSessionJustEnded("occ-date");

        Instant vecu = f.livedStart;
        rolloverJob.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(f.scheduleId).orElseThrow();
        assertThat(rolled.getStartsAt())
            .as("le job avance bien la ligne : c'est ce qui rendait la carte fausse")
            .isAfter(Instant.now());
        assertThat(rolled.getLastOccurrenceStart())
            .as("mais la séance vécue n'est plus perdue")
            .isEqualTo(vecu);

        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);
        SlotRecapDto card = setHostNote(f.hostToken, f.scheduleId, "Beau footing malgré la pluie.");

        assertThat(card.slotStartedAt())
            .as("la carte est datée du moment vécu")
            .isEqualTo(vecu);
        assertThat(card.slotStartedAt())
            .as("et jamais de la séance à venir")
            .isNotEqualTo(rolled.getStartsAt());
    }

    @Test
    void laFenetreDeContribution_seRefermeSeptJoursApresLaFinDeLaSeance() {
        Fixture f = weeklySlotWhoseSessionJustEnded("occ-fenetre");
        rolloverJob.rollPastRecurringSchedulesForward();

        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);
        SlotRecapDto card = setHostNote(f.hostToken, f.scheduleId, "Un mot.");

        assertThat(card.canContribute()).isTrue();
        assertThat(card.recapWindowClosesAt())
            .as("sept jours après la FIN de la séance vécue, pas après son début")
            .isEqualTo(f.livedEnd.plus(7, ChronoUnit.DAYS));
        assertThat(card.recapWindowClosesAt())
            .as("avant ce correctif la fenêtre repartait à chaque passage du rollover")
            .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test
    void deuxSeancesDuMemeCreneau_portentChacuneSaCarteEtSesPresences() {
        // Les deux impossibilités d'avant, dans un seul scénario : slot_recaps
        // était UNIQUE(schedule_id) et attendances UNIQUE(schedule_id, user_id).
        Fixture f = weeklySlotWhoseSessionJustEnded("occ-deux-seances");

        rolloverJob.rollPastRecurringSchedulesForward();
        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);
        setHostNote(f.hostToken, f.scheduleId, "Semaine une.");
        publish(f.hostToken, f.scheduleId);

        // La semaine suivante : on ramène la ligne dans le passé et on refait
        // tourner le job, exactement comme sept jours plus tard.
        Schedule slot = scheduleRepository.findById(f.scheduleId).orElseThrow();
        Instant semaineDeux = slot.getStartsAt();
        slot.setStartsAt(Instant.now().minus(3, ChronoUnit.HOURS));
        slot.setEndsAt(Instant.now().minus(2, ChronoUnit.HOURS));
        scheduleRepository.save(slot);
        Instant vecuDeux = slot.getStartsAt();

        rolloverJob.rollPastRecurringSchedulesForward();
        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);
        setHostNote(f.hostToken, f.scheduleId, "Semaine deux.");
        publish(f.hostToken, f.scheduleId);

        List<SlotRecapDto> cards = get(f.hostToken, "/api/programs/{id}/recaps", f.programId);

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(SlotRecapDto::hostNote)
            .containsExactly("Semaine deux.", "Semaine une.");
        assertThat(cards).extracting(SlotRecapDto::slotStartedAt)
            .as("triées de la séance la plus récente à la plus ancienne")
            .containsExactly(vecuDeux, f.livedStart);
        assertThat(semaineDeux).isNotEqualTo(f.livedStart);
    }

    @Test
    void lesTroisLecturesContextuelles_rendentLaCartePubliqueSansPositionNiRayon() {
        Fixture f = weeklySlotWhoseSessionJustEnded("occ-contexte");
        rolloverJob.rollPastRecurringSchedulesForward();

        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);
        setHostNote(f.hostToken, f.scheduleId, "Publique.");
        publish(f.hostToken, f.scheduleId);

        // Un tiers, sans position partagée et sans lien avec le programme.
        String outsider = registerAndLogin("occ-contexte-tiers@pair.app");

        assertThat(get(outsider, "/api/programs/{id}/recaps", f.programId))
            .extracting(SlotRecapDto::scheduleId).containsExactly(f.scheduleId);
        assertThat(get(outsider, "/api/activities/{id}/recaps", f.activityId))
            .extracting(SlotRecapDto::scheduleId).contains(f.scheduleId);
        assertThat(get(outsider, "/api/users/{id}/recaps", f.hostId))
            .extracting(SlotRecapDto::scheduleId).containsExactly(f.scheduleId);
    }

    @Test
    void uneCartePrivee_resteInvisibleAuxTiers_maisPasAQuiYEtait() {
        Fixture f = weeklySlotWhoseSessionJustEnded("occ-privee");
        rolloverJob.rollPastRecurringSchedulesForward();

        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);
        setHostNote(f.hostToken, f.scheduleId, "Pas encore publiée.");

        String outsider = registerAndLogin("occ-privee-tiers@pair.app");

        assertThat(get(outsider, "/api/programs/{id}/recaps", f.programId))
            .as("un visiteur ne voit que les cartes publiques")
            .isEmpty();
        assertThat(get(f.guestToken, "/api/programs/{id}/recaps", f.programId))
            .as("celui qui y était retrouve sa séance, publiée ou non")
            .extracting(SlotRecapDto::scheduleId).containsExactly(f.scheduleId);
        assertThat(get(f.hostToken, "/api/programs/{id}/recaps", f.programId))
            .as("l'auteur voit tout son programme")
            .hasSize(1);
        assertThat(get(f.guestToken, "/api/users/{id}/recaps", f.hostId))
            .as("mais un profil ne montre jamais la carte privée d'un tiers, "
                + "même à quelqu'un qui y était")
            .isEmpty();
    }

    // ————————————————————————— décor —————————————————————————

    private record Fixture(UUID scheduleId, UUID programId, UUID activityId, UUID hostId,
                           String hostToken, String guestToken,
                           Instant livedStart, Instant livedEnd) {}

    /**
     * Un créneau hebdomadaire dont la séance s'est terminée il y a deux heures,
     * son hôte et un participant — tous deux inscrits au créneau via la
     * présence, qui est ce que la carte exige.
     */
    private Fixture weeklySlotWhoseSessionJustEnded(String prefix) {
        String hostEmail = prefix + "-hote@pair.app";
        String hostToken = registerAndLogin(hostEmail);
        User host = userRepository.findByEmail(hostEmail).orElseThrow();

        String guestEmail = prefix + "-participant@pair.app";
        String guestToken = registerAndLogin(guestEmail);
        User guest = userRepository.findByEmail(guestEmail).orElseThrow();

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Footing hebdomadaire " + prefix)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant livedStart = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant livedEnd   = Instant.now().minus(2, ChronoUnit.HOURS);

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Parc de la Tête d'Or")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("Boulevard des Belges")
            .city("Lyon")
            .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
            .startsAt(livedStart)
            .endsAt(livedEnd)
            .recurrenceRule("FREQ=WEEKLY")
            .status(SlotStatus.OPEN)
            .isOpenToPartners(true)
            .build());

        // Le participant est inscrit au créneau : confirmer sa présence exige
        // d'y avoir eu sa place, et la carte exige la présence confirmée.
        participationRepository.save(SlotParticipation.builder()
            .schedule(schedule)
            .user(guest)
            .status(ParticipationStatus.CONFIRMED)
            .build());

        return new Fixture(schedule.getId(), program.getId(), activity.getId(), host.getId(),
            hostToken, guestToken, livedStart, livedEnd);
    }

    private void confirmPresence(String token, UUID scheduleId) {
        webTestClient.post()
            .uri("/api/attendances/{scheduleId}/confirm", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"wasPresent\":true}")
            .exchange()
            .expectStatus().isOk();
    }

    private SlotRecapDto setHostNote(String token, UUID scheduleId, String note) {
        return webTestClient.patch()
            .uri("/api/slots/{scheduleId}/recap/note", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"note\":\"" + note + "\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(SlotRecapDto.class)
            .returnResult()
            .getResponseBody();
    }

    private void publish(String token, UUID scheduleId) {
        webTestClient.patch()
            .uri("/api/slots/{scheduleId}/recap/visibility", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"visibility\":\"PUBLIC\"}")
            .exchange()
            .expectStatus().isOk();
    }

    private List<SlotRecapDto> get(String token, String uri, UUID id) {
        return webTestClient.get()
            .uri(uri, id)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(RECAP_LIST)
            .returnResult()
            .getResponseBody();
    }

    private String registerAndLogin(String email) {
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
}
