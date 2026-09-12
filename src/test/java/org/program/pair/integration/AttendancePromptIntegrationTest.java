package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.jobs.AttendancePromptJob;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P-BL-07 — une séance hebdomadaire terminée demande enfin « tu y étais ? », une
 * fois.
 *
 * <p>Quatre défauts se cumulaient sur les créneaux récurrents :
 *
 * <ol>
 *   <li>la requête lisait {@code ends_at} de la ligne, que le rollover avait déjà
 *       avancée : <b>une série ne recevait jamais aucune relance</b> ;</li>
 *   <li>le filtre « a déjà répondu » portait sur la ligne : avoir confirmé la
 *       première semaine dispensait de la question pour toujours ;</li>
 *   <li>le payload datait la question de la séance <b>suivante</b> ;</li>
 *   <li>une fenêtre de deux heures balayée toutes les heures posait la même
 *       question deux fois.</li>
 * </ol>
 *
 * <p><b>Pourquoi ces tests ne datent rien par {@code UPDATE}.</b> Les lignes sont
 * créées d'emblée dans le passé par le dépôt, jamais déplacées après coup : la
 * base est partagée par toute la suite et une seule JVM la porte, si bien qu'un
 * {@code UPDATE} mal borné se paierait dans une autre classe. Chaque méthode crée
 * ses comptes, son programme et son créneau, et n'observe que ses propres
 * notifications.
 */
class AttendancePromptIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;
    @Autowired AttendancePromptJob attendancePromptJob;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void uneSeanceHebdomadaireTerminee_doitProduireUneRelance_memeApresLeRollover() {
        Fixture f = serieDejaAvancee();

        attendancePromptJob.promptAttendanceConfirmation();

        await().atMost(Duration.ofSeconds(10))
            .until(() -> relances(f.participantId) == 1);
    }

    @Test
    void laRelance_doitPorterLaDateDeLaSeanceTerminee_pasCelleDeLaSuivante() {
        Fixture f = serieDejaAvancee();

        attendancePromptJob.promptAttendanceConfirmation();
        await().atMost(Duration.ofSeconds(10)).until(() -> relances(f.participantId) == 1);

        Map<String, Object> payload = dernierePayload(f.participantId);

        assertThat(payload.get("sessionAt"))
            .as("sessionAt est la clé que NotificationDto relit pour exposer "
                + "scheduledAt : elle doit dater la séance vécue, pas la suivante")
            .isEqualTo(f.occurrenceStart.toString());
        assertThat(payload.get("occurrenceStartsAt")).isEqualTo(f.occurrenceStart.toString());
        assertThat(payload.get("startsAt")).isEqualTo(f.occurrenceStart.toString());
        assertThat((String) payload.get("startsAt"))
            .as("la ligne, elle, porte déjà la semaine suivante")
            .isNotEqualTo(f.rowStartsAt.toString());
    }

    @Test
    void avoirReponduLaSemaineDerniere_neDoitPasEmpecherLaRelanceDeCetteSemaine() {
        // Le second défaut, et le plus discret : le filtre portait sur
        // l'existence d'une présence quelle qu'en soit la date.
        Fixture f = serieDejaAvancee();
        presenceDeLaSemaineDerniere(f);

        attendancePromptJob.promptAttendanceConfirmation();

        await().atMost(Duration.ofSeconds(10))
            .until(() -> relances(f.participantId) == 1);
    }

    @Test
    void avoirReponduPourCetteSeance_doitEmpecherLaRelance() {
        // Le symétrique : la question ne se pose plus à qui y a répondu pour
        // cette occurrence-là.
        Fixture f = serieDejaAvancee();
        presencePour(f, f.occurrenceStart);

        attendancePromptJob.promptAttendanceConfirmation();

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
            .until(() -> relances(f.participantId) == 0);
    }

    @Test
    void deuxPassagesDuJob_neDoiventProduireQuUneRelance() {
        // La fenêtre fait deux heures et le job tourne toutes les heures : sans
        // le marqueur (V110), la même séance était retenue deux fois.
        Fixture f = serieDejaAvancee();

        attendancePromptJob.promptAttendanceConfirmation();
        await().atMost(Duration.ofSeconds(10)).until(() -> relances(f.participantId) == 1);

        attendancePromptJob.promptAttendanceConfirmation();

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
            .until(() -> relances(f.participantId) == 1);

        assertThat(marqueur(f.slotId))
            .as("le marqueur porte le début de l'occurrence relancée, jamais "
                + "celui que porte la ligne")
            .isEqualTo(f.occurrenceStart);
    }

    @Test
    void uneSeanceUnique_doitToujoursEtreRelancee() {
        // Non-régression : le chemin qui marchait doit continuer.
        Fixture f = seanceUniqueTerminee();

        attendancePromptJob.promptAttendanceConfirmation();

        await().atMost(Duration.ofSeconds(10))
            .until(() -> relances(f.participantId) == 1);
    }

    @Test
    void uneSerieAnnulee_neDoitPasEtreRelancee() {
        // Une séance annulée n'a pas eu lieu : on ne demande pas si on y était
        // (P-BL-21, et le filtre de statut de la requête).
        Fixture f = serieAnnulee();

        attendancePromptJob.promptAttendanceConfirmation();

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
            .until(() -> relances(f.participantId) == 0);
    }

    // — lectures —

    private long relances(UUID userId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'ATTENDANCE_PROMPT'",
            Long.class, userId);
        return count == null ? 0L : count;
    }

    private Map<String, Object> dernierePayload(UUID userId) {
        String json = jdbcTemplate.queryForObject("""
            SELECT payload FROM notifications
            WHERE user_id = ? AND type = 'ATTENDANCE_PROMPT'
            ORDER BY sent_at DESC LIMIT 1
            """, String.class, userId);
        assertThat(json).isNotNull();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("payload illisible : " + json, e);
        }
    }

    private Instant marqueur(UUID slotId) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
            "SELECT attendance_prompted_for FROM schedules WHERE id = ?",
            java.sql.Timestamp.class, slotId);
        return ts == null ? null : ts.toInstant();
    }

    // — fixtures —

    private record Fixture(UUID slotId, UUID participantId, Instant occurrenceStart,
                           Instant occurrenceEnd, Instant rowStartsAt) {}

    /**
     * Une série hebdomadaire dont le rollover a déjà retiré la séance de la
     * semaine passée : la ligne annonce la semaine prochaine, et
     * {@code last_occurrence_start/end} portent la séance vécue.
     */
    private Fixture serieDejaAvancee() {
        return serie("FREQ=WEEKLY", SlotStatus.OPEN);
    }

    private Fixture serieAnnulee() {
        return serie("FREQ=WEEKLY", SlotStatus.CANCELLED);
    }

    private Fixture serie(String rrule, SlotStatus status) {
        Instant maintenant = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant occurrenceStart = maintenant.minus(Duration.ofHours(3));
        Instant occurrenceEnd = maintenant.minus(Duration.ofHours(2));
        Instant prochaine = occurrenceStart.plus(Duration.ofDays(7));

        Schedule slot = creneau(builder -> builder
            .recurrenceRule(rrule)
            .startsAt(prochaine)
            .endsAt(prochaine.plus(Duration.ofHours(1)))
            .lastOccurrenceStart(occurrenceStart)
            .lastOccurrenceEnd(occurrenceEnd)
            .status(status)
            .cancelledAt(status == SlotStatus.CANCELLED ? maintenant : null));

        return new Fixture(slot.getId(), inscrire(slot), occurrenceStart, occurrenceEnd, prochaine);
    }

    /** Une séance unique terminée il y a deux heures. */
    private Fixture seanceUniqueTerminee() {
        Instant maintenant = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant debut = maintenant.minus(Duration.ofHours(3));
        Instant fin = maintenant.minus(Duration.ofHours(2));

        Schedule slot = creneau(builder -> builder
            .startsAt(debut)
            .endsAt(fin)
            .status(SlotStatus.OPEN));

        return new Fixture(slot.getId(), inscrire(slot), debut, fin, debut);
    }

    private Schedule creneau(java.util.function.UnaryOperator<Schedule.ScheduleBuilder> dates) {
        User host = user("relance-hote");

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Relance de présence " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule.ScheduleBuilder builder = Schedule.builder()
            .program(program)
            .placeName("Séance en ligne")
            .placeType(PlaceType.ONLINE)
            .isOpenToPartners(true);

        return scheduleRepository.save(dates.apply(builder).build());
    }

    private UUID inscrire(Schedule slot) {
        User participant = user("relance-inscrit");
        SlotParticipation participation = new SlotParticipation();
        participation.setSchedule(slot);
        participation.setUser(participant);
        participation.setStatus(ParticipationStatus.CONFIRMED);
        participationRepository.save(participation);
        return participant.getId();
    }

    private void presenceDeLaSemaineDerniere(Fixture f) {
        presencePour(f, f.occurrenceStart.minus(Duration.ofDays(7)));
    }

    /**
     * Une présence datée d'une occurrence précise. L'insertion est directe et ne
     * touche qu'une ligne, la sienne — {@code confirm} refuserait une occurrence
     * que la ligne ne décrit plus.
     */
    private void presencePour(Fixture f, Instant occurrenceStart) {
        int inserted = jdbcTemplate.update("""
            INSERT INTO attendances (id, schedule_id, user_id, was_present, attended_at, confirmed_at)
            VALUES (gen_random_uuid(), ?, ?, TRUE, ?, NOW())
            """, f.slotId, f.participantId, java.sql.Timestamp.from(occurrenceStart));
        assertThat(inserted).isEqualTo(1);
    }

    private User user(String prefix) {
        return userRepository.save(User.builder()
            .email(uniqueEmail(prefix))
            .passwordHash("x")
            .displayName("Compte de test")
            .isActive(true)
            .build());
    }

    /** Garde-fou : la liste des statuts que la requête retient. */
    @Test
    void laRequete_neDoitRetenirQueLesStatutsOuvertsOuComplets() {
        Instant maintenant = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Schedule> retenus = scheduleRepository.findFinishedBetween(
            maintenant.minus(Duration.ofHours(3)), maintenant.minus(Duration.ofHours(1)),
            maintenant.minus(Duration.ofHours(5)), maintenant.minus(Duration.ofHours(3)));

        assertThat(retenus)
            .allSatisfy(s -> assertThat(s.getStatus())
                .isIn(SlotStatus.OPEN, SlotStatus.FULL));
    }
}
