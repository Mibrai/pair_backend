package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramEnrollmentService;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.program.ProgramService;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.dto.UpdateScheduleRequest;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un même Schedule peut recevoir des participants par deux mécanismes distincts :
 * UserProgram (inscription à un programme structuré) et SlotParticipation
 * (RSVP léger sur un créneau ouvert, voir SlotService). maxParticipants doit
 * être respecté par la somme des deux, jamais par l'un isolément — sinon le
 * créneau peut être sur-réservé en combinant les deux points d'entrée.
 */
class CombinedSlotCapacityIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotService slotService;
    @Autowired ProgramEnrollmentService programEnrollmentService;
    @Autowired ProgramService programService;
    @Autowired SlotParticipationRepository slotParticipationRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void capaciteCombinee_neDoitJamaisDepasserMaxParticipants() {
        String hostEmail = "capa-host@pair.app";
        String slotJoinerEmail = "capa-slot@pair.app";
        String programJoinerEmail = "capa-program@pair.app";

        register(hostEmail);
        register(slotJoinerEmail);
        register(programJoinerEmail);

        User host = userRepository.findByEmail(hostEmail).orElseThrow();
        User slotJoiner = userRepository.findByEmail(slotJoinerEmail).orElseThrow();
        User programJoiner = userRepository.findByEmail(programJoinerEmail).orElseThrow();

        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Yoga capacité limitée")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(Instant.now().plus(1, ChronoUnit.DAYS))
            .maxParticipants(1)
            .isOpenToPartners(true)
            .build());

        // Premier participant : rejoint via le RSVP léger (SlotParticipation).
        slotService.joinSlot(slotJoiner.getId(), schedule.getId(), new JoinSlotRequest(null));

        // Second participant : tente de rejoindre le MÊME créneau via
        // l'inscription au programme structuré (UserProgram). Doit être
        // refusé malgré le fait qu'aucun UserProgram n'existe encore pour ce
        // schedule — la capacité est bien vérifiée toutes sources confondues.
        //
        // L'assertion porte sur le CODE et non sur le message, et c'est une
        // correction : elle exigeait « full », le message anglais de
        // PROGRAM_SCHEDULE_FULL. Depuis SlotEntryGuard, ce scénario-ci — une
        // place, un inscrit — est refusé plus tôt et en français, par
        // SLOT_NOT_ACCEPTING_PARTICIPANTS. Ce que ce test doit fixer est qu'on
        // refuse, et pour une raison de capacité ; par quelle porte exactement
        // ne le regarde pas, et l'y attacher le casse au prochain garde-fou.
        assertThatThrownBy(() -> programEnrollmentService.joinProgram(
                programJoiner.getId(), program.getId(), schedule.getId()))
            .isInstanceOf(ValidationException.class)
            .extracting(e -> ((ValidationException) e).getErrorCode())
            .isIn(ErrorCode.SLOT_NOT_ACCEPTING_PARTICIPANTS, ErrorCode.SLOT_FULL);
    }

    /**
     * Ajouter des places fait remonter la file, autant qu'il y a de place.
     *
     * <p><b>Le défaut.</b> {@code updateSchedule} écrivait {@code maxParticipants}
     * et s'arrêtait là : ni {@code ParticipantCounter.refresh}, ni
     * {@code WaitlistPromoter}. Passer de quatre à six places laissait donc le
     * créneau {@code FULL} avec deux personnes qui l'attendaient dans la file, et
     * « vous êtes 1er » ne devenait jamais rien. L'organisateur avait pourtant
     * fait exactement le geste qui devait les faire entrer.
     */
    @Test
    void passerDeQuatreASixPlaces_doitPromouvoirDeuxPersonnesDeLaFile() {
        Terrain terrain = terrain(4);

        UUID premier = inscrit(terrain);
        UUID second = inscrit(terrain);
        UUID troisieme = inscrit(terrain);
        UUID quatrieme = inscrit(terrain);
        assertThat(statut(terrain.scheduleId())).isEqualTo(SlotStatus.FULL);

        UUID enFile = enFile(terrain);
        UUID enFileAussi = enFile(terrain);

        programService.updateSchedule(terrain.hostId(), terrain.scheduleId(), capacite(6));

        assertThat(statutDe(terrain.scheduleId(), enFile)).isEqualTo(ParticipationStatus.CONFIRMED);
        assertThat(statutDe(terrain.scheduleId(), enFileAussi)).isEqualTo(ParticipationStatus.CONFIRMED);
        // Six places prises sur six : le compteur et le statut suivent.
        assertThat(place(terrain.scheduleId()).getParticipantCount()).isEqualTo(6);
        assertThat(statut(terrain.scheduleId())).isEqualTo(SlotStatus.FULL);
        assertThat(List.of(premier, second, troisieme, quatrieme))
            .allSatisfy(id -> assertThat(statutDe(terrain.scheduleId(), id))
                .isEqualTo(ParticipationStatus.CONFIRMED));
    }

    /**
     * Baisser la capacité sous le nombre d'inscrits est accepté : personne n'est
     * désinscrit, le créneau passe simplement complet.
     *
     * <p>Choisir à la place de l'organisateur qui perd sa place serait pire que
     * l'incohérence temporaire d'un créneau à six inscrits pour quatre places. Ce
     * qui compte est qu'il n'en entre plus un septième.
     */
    @Test
    void passerDeSixAQuatrePlacesAvecSixInscrits_doitPasserLeCreneauComplet() {
        Terrain terrain = terrain(8);

        for (int i = 0; i < 6; i++) {
            inscrit(terrain);
        }
        assertThat(statut(terrain.scheduleId())).isEqualTo(SlotStatus.OPEN);

        programService.updateSchedule(terrain.hostId(), terrain.scheduleId(), capacite(4));

        assertThat(statut(terrain.scheduleId())).isEqualTo(SlotStatus.FULL);
        // Personne n'a été sorti : le compteur dit toujours six.
        assertThat(place(terrain.scheduleId()).getParticipantCount()).isEqualTo(6);
    }

    /** Un créneau annulé ne se modifie plus — ni son heure, ni son lieu, ni sa capacité. */
    @Test
    void modifierUnCreneauAnnule_doitEtreRefuse() {
        Terrain terrain = terrain(4);
        Schedule annule = place(terrain.scheduleId());
        annule.setStatus(SlotStatus.CANCELLED);
        scheduleRepository.saveAndFlush(annule);

        assertThatThrownBy(() ->
            programService.updateSchedule(terrain.hostId(), terrain.scheduleId(), capacite(6)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("annulé");
    }

    /**
     * Une fin avant le début était acceptée sans un mot : {@code UpdateScheduleRequest}
     * ne porte de contrainte que sur {@code maxParticipants}, et rien ne comparait
     * les deux dates. La séance devenait alors terminée avant d'avoir commencé,
     * pour tous ceux qui lisent {@code SlotTiming}.
     */
    @Test
    void unDebutApresLaFin_doitEtreRefuse() {
        Terrain terrain = terrain(4);
        Instant debut = Instant.now().plus(3, ChronoUnit.DAYS);

        assertThatThrownBy(() -> programService.updateSchedule(terrain.hostId(), terrain.scheduleId(),
            horaire(debut, debut.minus(1, ChronoUnit.HOURS))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("après son début");
    }

    /**
     * Déplacer le début dans le passé est refusé ; ne pas y toucher sur une séance
     * imminente reste permis.
     *
     * <p>La seconde moitié est la raison pour laquelle la règle porte sur « a
     * changé » et non sur « est futur » : corriger le lieu d'une séance qui
     * commence dans dix minutes est exactement le moment où on en a le plus besoin.
     */
    @Test
    void deplacerLeDebutDansLePasse_doitEtreRefuse_maisPasUneAutreCorrection() {
        Terrain terrain = terrain(4);

        assertThatThrownBy(() -> programService.updateSchedule(terrain.hostId(), terrain.scheduleId(),
            horaire(Instant.now().minus(1, ChronoUnit.HOURS), null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("passé");

        // Le début ne bouge pas : la correction passe, même à dix minutes du début.
        Schedule imminent = place(terrain.scheduleId());
        imminent.setStartsAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        imminent.setEndsAt(null);
        scheduleRepository.saveAndFlush(imminent);

        programService.updateSchedule(terrain.hostId(), terrain.scheduleId(), nomDuLieu("Studio corrigé"));
        assertThat(place(terrain.scheduleId()).getPlaceName()).isEqualTo("Studio corrigé");
    }

    // — outils —

    /**
     * Un hôte, un programme, un créneau à {@code places} places. Créé par méthode :
     * la base est partagée par toute la suite, et aucune donnée n'y est codée en dur.
     */
    private record Terrain(UUID hostId, UUID programId, UUID scheduleId) {}

    private Terrain terrain(int places) {
        User host = compte();
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Capacité " + UUID.randomUUID().toString().substring(0, 8))
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio " + UUID.randomUUID().toString().substring(0, 6))
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(Instant.now().plus(2, ChronoUnit.DAYS))
            .maxParticipants(places)
            .isOpenToPartners(true)
            .build());

        return new Terrain(host.getId(), program.getId(), schedule.getId());
    }

    private UUID inscrit(Terrain terrain) {
        User user = compte();
        slotService.joinSlot(user.getId(), terrain.scheduleId(), new JoinSlotRequest(null));
        return user.getId();
    }

    private UUID enFile(Terrain terrain) {
        User user = compte();
        slotService.joinWaitlist(user.getId(), terrain.scheduleId());
        return user.getId();
    }

    /** Un compte par appel, jamais d'adresse codée en dur : la base est partagée. */
    private User compte() {
        String email = uniqueEmail("capa");
        register(email);
        return userRepository.findByEmail(email).orElseThrow();
    }

    private Schedule place(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId).orElseThrow();
    }

    private SlotStatus statut(UUID scheduleId) {
        return place(scheduleId).getStatus();
    }

    private ParticipationStatus statutDe(UUID scheduleId, UUID userId) {
        return slotParticipationRepository.findByScheduleId(scheduleId).stream()
            .filter(p -> p.getUser().getId().equals(userId))
            .findFirst().orElseThrow()
            .getStatus();
    }

    private static UpdateScheduleRequest capacite(int places) {
        return requete(null, null, places, null);
    }

    private static UpdateScheduleRequest horaire(Instant debut, Instant fin) {
        return requete(debut, fin, null, null);
    }

    private static UpdateScheduleRequest nomDuLieu(String nom) {
        return requete(null, null, null, nom);
    }

    private static UpdateScheduleRequest requete(Instant debut, Instant fin,
                                                  Integer places, String nomDuLieu) {
        return new UpdateScheduleRequest(nomDuLieu, null, null, null, null, null, null,
            debut, fin, null, places, null, null, null, null, null);
    }

    private void register(String email) {
        RegisterRequest registerReq = new RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();
    }
}
