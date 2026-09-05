package org.program.pair.domain.program.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.CycleNudge;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CycleNudgeRepository;
import org.program.pair.repository.ProgramRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ce que le job fait des candidats qu'on lui donne : à qui il notifie, ce qu'il
 * refuse d'envoyer, et ce qu'il inscrit au registre.
 *
 * <p>La présélection appartient au {@code WHERE} des trois requêtes et se teste
 * contre une vraie base — voir {@code CycleNudgeJobIntegrationTest}. Ici on
 * vérifie la décision exacte, les deux plafonds, et l'extinction de l'étape 2
 * tant que son délai n'a pas été mesuré.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CycleNudgeJobTest {

    @Mock ProgramRepository programRepository;
    @Mock CycleNudgeRepository cycleNudgeRepository;
    @Mock NotificationService notificationService;

    @InjectMocks CycleNudgeJob job;

    // ------------------------------------------------------------------
    // Le piège de l'étape 7
    // ------------------------------------------------------------------

    @Test
    void etape7_neRelancePas_pendantQueLaSeanceALieu() {
        // Le cas que la présélection SQL laisse VRAIMENT passer, et que Java doit
        // écarter : une séance longue — une randonnée, un stage de week-end —
        // commencée il y a 25 h et qui finit dans une heure. MAX(starts_at) est
        // dépassé de plus de 24 h, donc la requête la retient ; elle n'est
        // pourtant pas terminée, et « ton cycle est bouclé » partirait pendant
        // qu'on y est.
        Program program = program();
        Schedule enCours = slot(program,
            Instant.now().minus(Duration.ofHours(25)),
            Instant.now().plus(Duration.ofHours(1)));

        givenStage7Candidates(program, List.of(enCours));

        job.sendCycleNudges();

        verify(notificationService, never())
            .notify(any(), eq(NotificationType.CYCLE_NUDGE), anyMap());
    }

    @Test
    void etape7_relance_quandLeCycleEstReferme() {
        Program program = program();
        Schedule finie = slot(program,
            Instant.now().minus(Duration.ofHours(30)),
            Instant.now().minus(Duration.ofHours(28)));

        givenStage7Candidates(program, List.of(finie));

        job.sendCycleNudges();

        Map<String, Object> payload = capturedPayload();
        assertThat(payload).containsEntry("stage", "7");
        assertThat(payload).containsEntry("programId", program.getId().toString());
        assertThat(payload).containsEntry("programTitle", program.getTitle());
    }

    @Test
    void etape7_inscritLHorizonAuRegistre() {
        Program program = program();
        Instant fin = Instant.now().minus(Duration.ofHours(28));
        Schedule finie = slot(program, fin.minus(Duration.ofHours(2)), fin);

        givenStage7Candidates(program, List.of(finie));

        job.sendCycleNudges();

        CycleNudge written = capturedNudges().get(0);
        assertThat(written.getStage()).isEqualTo((short) 7);
        assertThat(written.getHorizon()).isEqualTo(fin);
        assertThat(written.getProgram()).isSameAs(program);
    }

    // ------------------------------------------------------------------
    // Étape 4
    // ------------------------------------------------------------------

    @Test
    void etape4_neRelancePas_pendantQueLaSeanceALieu() {
        // Une séance en cours et la suivante dans huit jours : ignorer celle qui
        // a lieu rendrait vrai « la prochaine est dans plus de 48 h », et
        // « personne ne s'est inscrit » partirait pendant le cours.
        Program program = program();
        Schedule enCours = slot(program,
            Instant.now().minus(Duration.ofMinutes(20)),
            Instant.now().plus(Duration.ofMinutes(40)));
        Schedule loin = slot(program,
            Instant.now().plus(Duration.ofDays(8)), Instant.now().plus(Duration.ofDays(8)));

        givenStage4Candidates(program, List.of(enCours, loin));

        job.sendCycleNudges();

        verify(notificationService, never())
            .notify(any(), eq(NotificationType.CYCLE_NUDGE), anyMap());
    }

    @Test
    void etape4_relance_quandLaProchaineSeanceEstAuDelaDe48h() {
        Program program = program();
        Schedule loin = slot(program,
            Instant.now().plus(Duration.ofDays(5)), Instant.now().plus(Duration.ofDays(5)));

        givenStage4Candidates(program, List.of(loin));

        job.sendCycleNudges();

        assertThat(capturedPayload()).containsEntry("stage", "4");
    }

    // ------------------------------------------------------------------
    // Étape 2
    // ------------------------------------------------------------------

    @Test
    void etape2_resteEteinte_tantQueSonDelaiNaPasEteMesure() {
        // La valeur par défaut est zéro, et zéro veut dire « on ne sait pas
        // encore ». Livrer J+3 sur une intuition ferait du module le harcèlement
        // qu'il existe pour éviter.
        job.sendCycleNudges();

        verify(programRepository, never()).findStage2Candidates(any(), any());
    }

    @Test
    void etape2_relance_quandSonDelaiEstPose() {
        ReflectionTestUtils.setField(job, "stage2DelayDays", 7);
        Program program = program();
        givenStage2Candidates(program);

        job.sendCycleNudges();

        assertThat(capturedPayload()).containsEntry("stage", "2");
    }

    // ------------------------------------------------------------------
    // Les plafonds et les destinataires
    // ------------------------------------------------------------------

    @Test
    void unePersonneNeRecoitQuUneRelance_memeAvecPlusieursProgrammesDus() {
        // Le plafond de 48 h. Il ne peut pas se lire en base au fil de la passe :
        // le registre n'est écrit qu'au commit, et notify() est @Async. Sans
        // cumul en mémoire, cette personne en recevrait deux d'un coup.
        User auteur = user();
        Program premier = program(auteur);
        Program second = program(auteur);

        Instant fin = Instant.now().minus(Duration.ofHours(28));
        List<Schedule> schedules = new ArrayList<>();
        schedules.add(slot(premier, fin.minus(Duration.ofHours(2)), fin));
        schedules.add(slot(second, fin.minus(Duration.ofHours(2)), fin));

        when(programRepository.findStage7Candidates(any(), any()))
            .thenReturn(List.of(premier.getId(), second.getId()));
        when(programRepository.findWithOrganizerDetailsByIds(any()))
            .thenReturn(List.of(premier, second));
        when(programRepository.findSchedulesByProgramIds(any())).thenReturn(schedules);
        when(cycleNudgeRepository.countByUserIdAndSentAtAfter(any(), any())).thenReturn(0L);

        job.sendCycleNudges();

        verify(notificationService)
            .notify(eq(auteur.getId()), eq(NotificationType.CYCLE_NUDGE), anyMap());
        assertThat(capturedNudges()).hasSize(1);
    }

    @Test
    void personneNeEstRelance_siElleLaDejaEteDansLes48h() {
        Program program = program();
        Schedule finie = slot(program,
            Instant.now().minus(Duration.ofHours(30)),
            Instant.now().minus(Duration.ofHours(28)));

        givenStage7Candidates(program, List.of(finie));
        when(cycleNudgeRepository.countByUserIdAndSentAtAfter(any(), any())).thenReturn(1L);

        job.sendCycleNudges();

        verify(notificationService, never())
            .notify(any(), eq(NotificationType.CYCLE_NUDGE), anyMap());
        // Rien n'est marqué : le programme reste candidat pour plus tard.
        assertThat(capturedNudges()).isEmpty();
    }

    @Test
    void unCompteDesactiveNEstPasRelance() {
        User parti = user();
        parti.setIsActive(false);
        Program program = program(parti);
        Schedule finie = slot(program,
            Instant.now().minus(Duration.ofHours(30)),
            Instant.now().minus(Duration.ofHours(28)));

        givenStage7Candidates(program, List.of(finie));

        job.sendCycleNudges();

        verify(notificationService, never())
            .notify(any(), eq(NotificationType.CYCLE_NUDGE), anyMap());
    }

    @Test
    void uneExecutionRatee_neFaitPasEchouerLeJob() {
        when(programRepository.findStage7Candidates(any(), any()))
            .thenThrow(new IllegalStateException("base indisponible"));

        job.sendCycleNudges();
        // Aucune exception ne remonte : la passe suivante reprendra les mêmes
        // programmes, rien n'ayant été marqué.
    }

    // ------------------------------------------------------------------

    private void givenStage7Candidates(Program program, List<Schedule> schedules) {
        when(programRepository.findStage7Candidates(any(), any()))
            .thenReturn(List.of(program.getId()));
        stubLoad(program, schedules);
    }

    private void givenStage4Candidates(Program program, List<Schedule> schedules) {
        when(programRepository.findStage4Candidates(any(), any(), any()))
            .thenReturn(List.of(program.getId()));
        stubLoad(program, schedules);
    }

    private void givenStage2Candidates(Program program) {
        when(programRepository.findStage2Candidates(any(), any()))
            .thenReturn(List.of(program.getId()));
        stubLoad(program, List.of());
    }

    private void stubLoad(Program program, List<Schedule> schedules) {
        when(programRepository.findWithOrganizerDetailsByIds(any())).thenReturn(List.of(program));
        when(programRepository.findSchedulesByProgramIds(any())).thenReturn(schedules);
        when(cycleNudgeRepository.countByUserIdAndSentAtAfter(any(), any())).thenReturn(0L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService)
            .notify(any(), eq(NotificationType.CYCLE_NUDGE), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<CycleNudge> capturedNudges() {
        ArgumentCaptor<Collection<CycleNudge>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(cycleNudgeRepository).saveAll(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("Sophie Martin");
        user.setIsActive(true);
        return user;
    }

    private static Program program() {
        return program(user());
    }

    private static Program program(User author) {
        Activity activity = new Activity();
        activity.setId(UUID.randomUUID());
        activity.setName("Course à pied");

        UserActivity userActivity = UserActivity.builder()
            .user(author)
            .activity(activity)
            .build();
        userActivity.setId(UUID.randomUUID());

        Program program = Program.builder()
            .userActivity(userActivity)
            .title("Course du mardi")
            .build();
        program.setId(UUID.randomUUID());
        return program;
    }

    private static Schedule slot(Program program, Instant startsAt, Instant endsAt) {
        Schedule slot = Schedule.builder()
            .program(program)
            .startsAt(startsAt)
            .endsAt(endsAt)
            .status(SlotStatus.OPEN)
            .build();
        slot.setId(UUID.randomUUID());
        slot.setCreatedAt(Instant.now().minus(Duration.ofDays(4)));
        return slot;
    }
}
