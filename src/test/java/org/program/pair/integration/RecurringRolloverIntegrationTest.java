package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.jobs.RecurringSlotRolloverJob;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande 4 de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md, au niveau du
 * modèle : le job qui maintient l'unique occurrence bookable d'un créneau
 * récurrent doit lire la RRULE, et non ajouter sept jours en aveugle.
 *
 * <p>C'est ce job qui rend corrects, sans les toucher, tous les chemins de
 * lecture qui s'appuient sur {@code starts_at} — d'où des tests sur l'état en
 * base plutôt que sur une réponse HTTP.
 *
 * <p>Les créneaux sont semés directement en base — c'est le seul moyen de poser
 * un {@code starts_at} passé et une règle de récurrence choisie. En revanche
 * tout ce qui <b>annule</b> passe par les routes HTTP ({@code POST
 * /slots/{id}/cancel}, {@code DELETE /programs/{id}/schedules/{id}}) : ce sont
 * elles qui décident de l'état écrit, et les tests d'annulation seraient sans
 * valeur s'ils le posaient eux-mêmes. D'où un organisateur enregistré et
 * connecté pour ces cas-là.
 */
class RecurringRolloverIntegrationTest extends AbstractIntegrationTest {

    @Autowired RecurringSlotRolloverJob job;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    @Test
    void unCreneauHebdomadairePasse_doitEtreAvanceAUneOccurrenceFuture() {
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;BYDAY=MO", lastMonday(), Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(rolled.getStartsAt()).isAfter(Instant.now());
        assertThat(rolled.getStartsAt().atZone(ZONE).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(rolled.getStatus()).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void bydayMultiJours_doitPouvoirAtterrirSurLeSecondJour() {
        // Le cas que l'ancien job ne pouvait pas produire : en avançant de sept
        // jours, un créneau posé un lundi restait lundi pour toujours. Avec
        // MO,WE, la prochaine occurrence après un lundi passé est un mercredi.
        Instant seed = lastMonday();
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;BYDAY=MO,WE", seed, Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(rolled.getStartsAt()).isAfter(Instant.now());
        assertThat(rolled.getStartsAt().atZone(ZONE).getDayOfWeek())
            .as("l'ancien job ne savait produire que des lundis")
            .isIn(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        // La prochaine occurrence est à moins d'une semaine : avec deux jours par
        // semaine, l'écart maximal est de quatre jours.
        assertThat(rolled.getStartsAt())
            .isBefore(Instant.now().plus(5, ChronoUnit.DAYS));
    }

    @Test
    void uneSerieCloseParUntil_doitResterPassee() {
        // L'ancien UPDATE avançait tout créneau récurrent passé, sans lire la
        // règle : il ressuscitait des séries terminées.
        Instant seed = Instant.now().minus(60, ChronoUnit.DAYS);
        String until = "UNTIL=" + ZonedDateTime.ofInstant(
                Instant.now().minus(30, ChronoUnit.DAYS), ZoneId.of("UTC"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;" + until, seed, Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        Schedule untouched = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(untouched.getStartsAt())
            .as("une série close ne doit pas repartir dans le futur")
            .isEqualTo(seed);
    }

    @Test
    void laDureeDuCreneau_doitEtrePreservee() {
        UUID_Holder holder = new UUID_Holder(createSchedule(
            "FREQ=WEEKLY;BYDAY=MO", lastMonday(), Duration.ofMinutes(90)));

        job.rollPastRecurringSchedulesForward();

        Schedule rolled = scheduleRepository.findById(holder.id).orElseThrow();
        assertThat(Duration.between(rolled.getStartsAt(), rolled.getEndsAt()))
            .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void unCreneauNonRecurrentPasse_neDoitPasEtreTouche() {
        Instant seed = Instant.now().minus(3, ChronoUnit.DAYS);
        UUID_Holder holder = new UUID_Holder(createSchedule(null, seed, Duration.ofHours(1)));

        job.rollPastRecurringSchedulesForward();

        assertThat(scheduleRepository.findById(holder.id).orElseThrow().getStartsAt())
            .isEqualTo(seed);
    }

    // — P-BL-02 : une série annulée ne se rouvre plus la semaine suivante —

    @Test
    void unCreneauHebdomadaireAnnule_neDoitPasEtreAvance() {
        // Le défaut relevé en production : la requête du job ne retenait que
        // « récurrent et commencé », le job posait OPEN, et le créneau annulé
        // réapparaissait dans le fil sept jours plus tard avec ses inscrits.
        RecurringSlot slot = seedRecurringSlot("FREQ=WEEKLY;BYDAY=MO", lastMonday(),
            Duration.ofHours(1), 8);
        cancelByRoute(slot);

        job.rollPastRecurringSchedulesForward();

        Schedule after = scheduleRepository.findById(slot.scheduleId()).orElseThrow();
        assertThat(after.getStatus())
            .as("CANCELLED est terminal : aucun job ne fait plus changer cette ligne d'état")
            .isEqualTo(SlotStatus.CANCELLED);
        assertThat(after.getStartsAt())
            .as("la séance annulée ne doit pas repartir à la semaine suivante")
            .isEqualTo(slot.startsAt());
        assertThat(after.getLastOccurrenceStart())
            .as("aucune occurrence n'a été retirée : la ligne n'a pas bougé du tout")
            .isNull();
    }

    @Test
    void unCreneauHebdomadaireSupprimeAvecInscrits_neDoitPasEtreAvance() {
        // Même scénario par DELETE, et ce n'est pas un doublon : les deux routes
        // écrivent CANCELLED par deux chemins différents, et celui-ci ne
        // renseigne pas cancelled_at. Un filtre qui se serait appuyé sur la date
        // d'annulation plutôt que sur le statut aurait laissé passer exactement
        // ces lignes-là — ce sont aussi celles que la reprise de données ne peut
        // pas retrouver autrement que par la notification émise.
        //
        // « AvecInscrits » est nécessaire : sans personne pour compter dessus,
        // deleteSchedule supprime la ligne pour de bon et il n'y a plus rien à
        // avancer.
        RecurringSlot slot = seedRecurringSlot("FREQ=WEEKLY", futureStart(),
            Duration.ofHours(1), 8);
        join(registerAndLogin(uniqueEmail("rollover-inscrit")), slot.scheduleId());

        deleteByRoute(slot);

        assertThat(statusInDb(slot.scheduleId())).isEqualTo("CANCELLED");
        assertThat(cancelledAtInDb(slot.scheduleId()))
            .as("DELETE ne pose aucune date d'annulation, et c'est ce qui rend la reprise difficile")
            .isNull();

        Instant antidate = antedateByWeeks(slot, 2);

        job.rollPastRecurringSchedulesForward();

        Schedule after = scheduleRepository.findById(slot.scheduleId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SlotStatus.CANCELLED);
        assertThat(after.getStartsAt())
            .as("un créneau supprimé avec des inscrits ne doit pas leur revenir la semaine suivante")
            .isEqualTo(antidate);
        assertThat(after.getParticipantCount())
            .as("le compteur n'est pas recalculé non plus : rien n'a touché la ligne")
            .isEqualTo(1);
    }

    @Test
    void unCreneauHebdomadaireAnnule_neDoitPlusProduireDeRappel() {
        // C'est la conséquence qui coûtait le plus cher : le rappel J-2h filtre
        // OPEN/FULL, donc il repartait vers les anciens inscrits dès que le
        // rollover avait rouvert la ligne.
        RecurringSlot annule = seedRecurringSlot("FREQ=WEEKLY;BYDAY=MO", lastMonday(),
            Duration.ofHours(1), 8);
        cancelByRoute(annule);

        // Témoin de la même forme, non annulé : sans lui, « absent des rappels »
        // resterait vrai même si la requête ne rendait plus rien du tout.
        RecurringSlot temoin = seedRecurringSlot("FREQ=WEEKLY;BYDAY=MO", lastMonday(),
            Duration.ofHours(1), 8);

        job.rollPastRecurringSchedulesForward();

        Instant now = Instant.now();
        List<UUID> dus = scheduleRepository.findDueForReminder(now, now.plus(15, ChronoUnit.DAYS))
            .stream()
            .map(Schedule::getId)
            .toList();

        assertThat(dus)
            .as("le témoin prouve que la requête rend bien ce genre de créneau")
            .contains(temoin.scheduleId());
        assertThat(dus)
            .as("aucun rappel ne doit partir pour une séance annulée")
            .doesNotContain(annule.scheduleId());
    }

    @Test
    void unCreneauHebdomadaireComplet_doitResterCompletApresRollover() {
        // Non-régression du compteur : la garde d'annulation ne doit pas se payer
        // d'un créneau complet qui rouvrirait des places inexistantes. Le job
        // repose OPEN, puis participantCounter.refresh reprend FULL.
        RecurringSlot slot = seedRecurringSlot("FREQ=WEEKLY", futureStart(),
            Duration.ofHours(1), 1);
        join(registerAndLogin(uniqueEmail("rollover-complet")), slot.scheduleId());

        assertThat(scheduleRepository.findById(slot.scheduleId()).orElseThrow().getStatus())
            .as("une place, un inscrit : le créneau est complet avant le rollover")
            .isEqualTo(SlotStatus.FULL);

        Instant antidate = antedateByWeeks(slot, 2);

        job.rollPastRecurringSchedulesForward();

        Schedule after = scheduleRepository.findById(slot.scheduleId()).orElseThrow();
        assertThat(after.getStartsAt())
            .as("la série continue : la ligne est bien avancée")
            .isAfter(Instant.now());
        assertThat(after.getStartsAt()).isNotEqualTo(antidate);
        assertThat(after.getParticipantCount())
            .as("l'inscription tient d'une occurrence à la suivante")
            .isEqualTo(1);
        assertThat(after.getStatus())
            .as("un créneau récurrent complet le reste après rollover")
            .isEqualTo(SlotStatus.FULL);
    }

    // — helpers —

    /** Petit porteur d'id : les entités sont détachées entre les transactions. */
    private record UUID_Holder(java.util.UUID id) {}

    private java.util.UUID createSchedule(String rule, Instant startsAt, Duration duration) {
        User owner = userRepository.save(User.builder()
            .email("rollover-" + java.util.UUID.randomUUID() + "@pair.app")
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Rollover Host")
            .isActive(true)
            .build());

        Activity activity = activityRepository.findAll().get(0);
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(owner).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Programme rollover")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Salle rollover")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Rollover")
            .showExactAddress(true)
            .location(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), 4326)
                .createPoint(new org.locationtech.jts.geom.Coordinate(2.35, 48.85)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(duration))
            .recurrenceRule(rule)
            .maxParticipants(8)
            .isOpenToPartners(true)
            .status(SlotStatus.PAST)
            .build()).getId();
    }

    /** Le lundi le plus récent déjà passé, à 18h30 locales. */
    private Instant lastMonday() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime monday = now.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .withHour(18).withMinute(30).withSecond(0).withNano(0);
        // La séance semée doit être TERMINÉE, pas seulement commencée : le job
        // écarte délibérément les séances en cours, et ne pas la reculer rendait
        // ces tests rouges tous les lundis entre 18h30 et 20h00 — la seule heure
        // où un lundi « déjà passé » est en réalité en train d'avoir lieu. Un
        // jour entier de marge couvre toutes les durées semées ici.
        if (monday.toInstant().isAfter(Instant.now().minus(1, ChronoUnit.DAYS))) {
            monday = monday.minusWeeks(1);
        }
        return monday.toInstant();
    }

    // — helpers des tests d'annulation (P-BL-02) —

    /**
     * Un créneau récurrent semé en base, avec de quoi l'annuler par une route :
     * son programme (le {@code DELETE} passe par lui) et le jeton de son
     * organisateur. {@code startsAt} et {@code duration} sont conservés pour
     * pouvoir antidater la ligne sans la relire.
     */
    private record RecurringSlot(UUID scheduleId, UUID programId, String ownerToken,
                                 Instant startsAt, Duration duration) {}

    /**
     * Un début de séance à venir, tronqué à la seconde.
     *
     * <p>À venir, parce que rejoindre un créneau passé est refusé
     * ({@code SLOT_ALREADY_STARTED}) : les cas qui ont besoin d'un inscrit
     * sèment dans le futur, puis antidatent une fois l'inscription posée.
     *
     * <p>Tronqué, parce que {@code timestamptz} ne garde que la microseconde :
     * un instant en nanosecondes ne se relit pas égal à lui-même après un
     * aller-retour par {@link #antedateByWeeks}.
     */
    private Instant futureStart() {
        return Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Recule la séance de {@code weeks} semaines entières — <b>sur ce créneau et
     * sur lui seul</b>, la suite entière partageant une base.
     *
     * <p>Des semaines entières et non un nombre de jours quelconque : la règle
     * hebdomadaire doit continuer de tomber sur le même jour, de sorte que la
     * prochaine occurrence reste celle qui avait été semée.
     *
     * @return le {@code starts_at} désormais en base.
     */
    private Instant antedateByWeeks(RecurringSlot slot, int weeks) {
        Instant startsAt = slot.startsAt().minus(7L * weeks, ChronoUnit.DAYS);
        int touched = jdbcTemplate.update(
            "UPDATE schedules SET starts_at = ?, ends_at = ? WHERE id = ?",
            java.sql.Timestamp.from(startsAt),
            java.sql.Timestamp.from(startsAt.plus(slot.duration())),
            slot.scheduleId());
        assertThat(touched)
            .as("l'antidatage doit porter sur la seule ligne du test")
            .isEqualTo(1);
        return startsAt;
    }

    private RecurringSlot seedRecurringSlot(String rule, Instant startsAt,
                                             Duration duration, int maxParticipants) {
        String email = uniqueEmail("rollover-host");
        String token = registerAndLogin(email);
        User owner = userRepository.findByEmail(email).orElseThrow();

        Activity activity = activityRepository.findAll().get(0);
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(owner).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Programme rollover annulation")
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Salle rollover")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Rollover")
            .showExactAddress(true)
            .location(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), 4326)
                .createPoint(new org.locationtech.jts.geom.Coordinate(2.35, 48.85)))
            .startsAt(startsAt)
            .endsAt(startsAt.plus(duration))
            .recurrenceRule(rule)
            .maxParticipants(maxParticipants)
            .isOpenToPartners(true)
            // OPEN et non PAST : rejoindre et annuler l'exigent, et c'est aussi
            // l'état d'un créneau récurrent vivant.
            .status(SlotStatus.OPEN)
            .build());

        return new RecurringSlot(schedule.getId(), program.getId(), token, startsAt, duration);
    }

    /** {@code POST /api/slots/{id}/cancel} — la route d'annulation explicite. */
    private void cancelByRoute(RecurringSlot slot) {
        webTestClient.post().uri("/api/slots/{id}/cancel", slot.scheduleId())
            .headers(h -> h.setBearerAuth(slot.ownerToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("reason", "Le gymnase est ferme"))
            .exchange().expectStatus().isNoContent();
    }

    /** {@code DELETE /api/programs/{id}/schedules/{id}} — la « suppression ». */
    private void deleteByRoute(RecurringSlot slot) {
        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}",
                slot.programId(), slot.scheduleId())
            .headers(h -> h.setBearerAuth(slot.ownerToken()))
            .exchange().expectStatus().isNoContent();
    }

    private void join(String token, UUID scheduleId) {
        webTestClient.post().uri("/api/slots/{id}/join", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private String statusInDb(UUID scheduleId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM schedules WHERE id = ?", String.class, scheduleId);
    }

    private Object cancelledAtInDb(UUID scheduleId) {
        return jdbcTemplate.queryForMap(
            "SELECT cancelled_at FROM schedules WHERE id = ?", scheduleId).get("cancelled_at");
    }

    private String registerAndLogin(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Rollover"))
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
