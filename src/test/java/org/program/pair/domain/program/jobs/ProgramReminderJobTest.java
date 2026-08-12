package org.program.pair.domain.program.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le rappel T-2h : qui le reçoit, et ce que le job écrit derrière lui.
 *
 * <p>La sélection des créneaux appartient au {@code WHERE} de
 * {@code findDueForReminder} et se teste contre une vraie base — voir
 * {@code ProgramReminderJobIntegrationTest}. Ici on teste ce que le job fait
 * d'un créneau qu'on lui donne : à qui il notifie, ce qu'il marque, et ce qu'il
 * fait quand un destinataire n'existe plus.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgramReminderJobTest {

    @Mock ScheduleRepository scheduleRepository;
    @Mock SlotAudience slotAudience;
    @Mock NotificationService notificationService;
    @Mock UserRepository userRepository;

    @InjectMocks ProgramReminderJob job;

    @Test
    void doitNotifierTousLesInscrits_pasSeulementLHote() {
        // La question n°1 du client. Tous les inscrits — l'hôte compris, mais
        // pas lui seul.
        Schedule slot = slot(Instant.now().plusSeconds(7_200));
        UUID host = UUID.randomUUID();
        UUID participant = UUID.randomUUID();
        UUID suiveur = UUID.randomUUID();

        when(scheduleRepository.findDueForReminder(any(), any())).thenReturn(List.of(slot));
        when(slotAudience.participantIds(slot)).thenReturn(List.of(host, participant, suiveur));
        when(userRepository.existsById(any())).thenReturn(true);

        job.sendUpcomingSlotReminders();

        verify(notificationService).notify(eq(host), eq(NotificationType.PROGRAM_REMINDER), anyMap());
        verify(notificationService).notify(eq(participant), eq(NotificationType.PROGRAM_REMINDER), anyMap());
        verify(notificationService).notify(eq(suiveur), eq(NotificationType.PROGRAM_REMINDER), anyMap());
    }

    @Test
    void lePayload_doitPorterLeDebutDuCreneau_pasLHeureDEnvoi() {
        // La question n°3 du client. sessionAt — d'où NotificationDto dérive
        // scheduledAt — porte le début de la séance. Les deux valeurs diffèrent
        // de deux heures ; les confondre afficherait « maintenant » deux heures
        // trop tôt sur le compte à rebours client.
        Instant debut = Instant.parse("2026-08-17T18:30:00Z");
        Schedule slot = slot(debut);

        when(scheduleRepository.findDueForReminder(any(), any())).thenReturn(List.of(slot));
        when(slotAudience.participantIds(slot)).thenReturn(List.of(UUID.randomUUID()));
        when(userRepository.existsById(any())).thenReturn(true);

        job.sendUpcomingSlotReminders();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(any(), eq(NotificationType.PROGRAM_REMINDER), payload.capture());

        assertThat(payload.getValue().get("sessionAt")).isEqualTo("2026-08-17T18:30:00Z");
        assertThat(payload.getValue().get("programTitle")).isEqualTo("Longueurs du soir");
    }

    @Test
    void doitMarquerLeCreneau_pourLInstantDeDebutTraite() {
        // Marqué pour CE starts_at, pas d'un booléen : c'est ce qui fait qu'un
        // créneau déplacé redevient éligible sans qu'on ait à le replanifier.
        Instant debut = Instant.parse("2026-08-17T18:30:00Z");
        Schedule slot = slot(debut);

        when(scheduleRepository.findDueForReminder(any(), any())).thenReturn(List.of(slot));
        when(slotAudience.participantIds(slot)).thenReturn(List.of(UUID.randomUUID()));
        when(userRepository.existsById(any())).thenReturn(true);

        job.sendUpcomingSlotReminders();

        assertThat(slot.getReminderSentFor()).isEqualTo(debut);
        verify(scheduleRepository).save(slot);
    }

    @Test
    void creneauSansAucunInscrit_doitQuandMemeEtreMarque() {
        // Sinon il serait rebalayé toutes les cinq minutes jusqu'à son début,
        // pour ne rien envoyer à chaque passage.
        Schedule slot = slot(Instant.now().plusSeconds(7_200));

        when(scheduleRepository.findDueForReminder(any(), any())).thenReturn(List.of(slot));
        when(slotAudience.participantIds(slot)).thenReturn(List.of());

        job.sendUpcomingSlotReminders();

        assertThat(slot.getReminderSentFor()).isEqualTo(slot.getStartsAt());
        verify(notificationService, never()).notify(any(), any(), anyMap());
    }

    @Test
    void destinataireSupprime_doitEtreIgnore_sansEmporterLesAutres() {
        // getReferenceById dans notify() ne vérifie pas l'existence : un compte
        // supprimé entre l'inscription et le rappel ferait échouer l'écriture, et
        // l'exception emporterait les destinataires suivants du même créneau.
        Schedule slot = slot(Instant.now().plusSeconds(7_200));
        UUID vivant = UUID.randomUUID();
        UUID supprime = UUID.randomUUID();

        when(scheduleRepository.findDueForReminder(any(), any())).thenReturn(List.of(slot));
        when(slotAudience.participantIds(slot)).thenReturn(List.of(supprime, vivant));
        when(userRepository.existsById(supprime)).thenReturn(false);
        when(userRepository.existsById(vivant)).thenReturn(true);

        job.sendUpcomingSlotReminders();

        verify(notificationService, never()).notify(eq(supprime), any(), anyMap());
        verify(notificationService).notify(eq(vivant), eq(NotificationType.PROGRAM_REMINDER), anyMap());
    }

    @Test
    void uneErreurSurUnCreneau_neDoitPasEmpecherLExecutionSuivante() {
        // Le job attrape : une exécution ratée laisse les créneaux non marqués,
        // donc éligibles au passage d'après. Il ne doit pas propager.
        when(scheduleRepository.findDueForReminder(any(), any()))
            .thenThrow(new RuntimeException("base indisponible"));

        job.sendUpcomingSlotReminders();

        verify(notificationService, never()).notify(any(), any(), anyMap());
    }

    @Test
    void doitBalayerUneFenetreDeDeuxHeures() {
        // Le seuil produit, et celui sur lequel le client aligne sa fenêtre
        // d'imminence. Les deux doivent rester égaux.
        when(scheduleRepository.findDueForReminder(any(), any())).thenReturn(List.of());

        job.sendUpcomingSlotReminders();

        ArgumentCaptor<Instant> maintenant = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> horizon = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleRepository, times(1)).findDueForReminder(maintenant.capture(), horizon.capture());

        assertThat(java.time.Duration.between(maintenant.getValue(), horizon.getValue()))
            .isEqualTo(java.time.Duration.ofHours(2));
    }

    private static Schedule slot(Instant startsAt) {
        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Longueurs du soir");

        Schedule slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        slot.setStartsAt(startsAt);
        return slot;
    }
}
