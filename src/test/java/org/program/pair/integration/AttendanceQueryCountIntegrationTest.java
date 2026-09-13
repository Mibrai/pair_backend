package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.AttendanceService;
import org.program.pair.domain.attendance.dto.PendingAttendanceDto;
import org.program.pair.domain.attendance.jobs.AttendancePromptJob;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotOccurrence;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-15 — les présences ne coûtent plus une requête par personne ni par séance.
 *
 * <p>La relance posait « a-t-il répondu ? » inscrit par inscrit, chaque heure ;
 * la liste des présences à confirmer, séance par séance, à chaque ouverture. Les
 * deux se lisent désormais en une requête sur {@code attendances}.
 */
class AttendanceQueryCountIntegrationTest extends AbstractIntegrationTest {

    @Autowired AttendanceService attendanceService;
    @Autowired AttendancePromptJob attendancePromptJob;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void laListeDesPresencesAConfirmer_neGranditPasEnRequetesAvecLeNombreDeSeances() {
        UUID hoteDeTrois = hoteAvecSeancesPassees(3);
        UUID hoteDeTrente = hoteAvecSeancesPassees(30);

        SqlCompteur.Releve<List<PendingAttendanceDto>> petit =
            SqlCompteur.pendant(() -> attendanceService.getPending(hoteDeTrois));
        SqlCompteur.Releve<List<PendingAttendanceDto>> grand =
            SqlCompteur.pendant(() -> attendanceService.getPending(hoteDeTrente));

        assertThat(petit.resultat()).hasSize(3);
        assertThat(grand.resultat()).hasSize(30);
        assertThat(SqlCompteur.lisant(grand.requetes(), "attendances"))
            .as("une requête sur les présences, quel que soit le nombre de séances")
            .isEqualTo(SqlCompteur.lisant(petit.requetes(), "attendances"))
            .isEqualTo(1);
    }

    @Test
    void laRelanceDUnCreneauATrenteInscrits_nePoseQuUneQuestionPourLesReponses() {
        Schedule creneau = creneauPasseAvecInscrits(30);
        SlotOccurrence occurrence = new SlotOccurrence(creneau.getStartsAt(), creneau.getEndsAt());
        Object job = AopUtils.isAopProxy(attendancePromptJob) ? cible(attendancePromptJob) : attendancePromptJob;

        SqlCompteur.Releve<List<UUID>> releve = SqlCompteur.pendant(() -> transactionTemplate.execute(
            status -> ReflectionTestUtils.<List<UUID>>invokeMethod(job, "unconfirmedParticipantIds",
                scheduleRepository.findById(creneau.getId()).orElseThrow(), occurrence)));

        assertThat(releve.resultat()).hasSize(31); // l'hôte et ses trente inscrits
        assertThat(SqlCompteur.lisant(releve.requetes(), "attendances")).isEqualTo(1);
    }

    private static Object cible(Object proxy) {
        try {
            return ((Advised) proxy).getTargetSource().getTarget();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // — décor —

    private UUID hoteAvecSeancesPassees(int seances) {
        User hote = utilisateur("att-qc-hote");
        Program programme = programmeDe(hote);
        for (int i = 0; i < seances; i++) {
            Instant debut = Instant.now().minus(i + 2L, ChronoUnit.HOURS);
            scheduleRepository.save(seance(programme, debut));
        }
        return hote.getId();
    }

    private Schedule creneauPasseAvecInscrits(int inscrits) {
        User hote = utilisateur("att-qc-relance");
        Schedule creneau = scheduleRepository.save(
            seance(programmeDe(hote), Instant.now().minus(3, ChronoUnit.HOURS)));
        for (int i = 0; i < inscrits; i++) {
            participationRepository.save(SlotParticipation.builder()
                .schedule(creneau)
                .user(utilisateur("att-qc-inscrit"))
                .status(ParticipationStatus.CONFIRMED)
                .build());
        }
        return creneau;
    }

    private Program programmeDe(User hote) {
        Activity activite = activityRepository.findAll().get(0);
        UserActivity ua = userActivityRepository.save(
            UserActivity.builder().user(hote).activity(activite).build());
        return programRepository.save(Program.builder()
            .userActivity(ua)
            .title("Présences " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
    }

    private Schedule seance(Program programme, Instant debut) {
        return Schedule.builder()
            .program(programme)
            .placeName("Salle")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue des Présences, Nantes")
            .location(geometryFactory.createPoint(new Coordinate(-1.5536, 47.2184)))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build();
    }

    private User utilisateur(String prefixe) {
        return userRepository.save(User.builder()
            .email(uniqueEmail(prefixe))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName(prefixe)
            .isActive(true)
            .build());
    }
}
