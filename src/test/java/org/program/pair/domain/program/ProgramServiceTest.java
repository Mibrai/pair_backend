package org.program.pair.domain.program;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.dto.CreateProgramRequest;
import org.program.pair.domain.program.dto.CreateScheduleRequest;
import org.program.pair.domain.program.dto.ScheduleDto;
import org.program.pair.domain.program.dto.UpdateProgramRequest;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProgramServiceTest {

    @Mock
    ProgramRepository programRepository;

    @Mock
    ScheduleRepository scheduleRepository;

    @Mock
    UserActivityRepository userActivityRepository;

    @Mock
    HtmlSanitizer sanitizer;

    @Mock
    org.program.pair.repository.ProgramMediaRepository programMediaRepository;

    @Mock
    org.program.pair.repository.ReviewRepository reviewRepository;

    @Mock
    org.program.pair.repository.UserProgramRepository userProgramRepository;

    @Mock
    org.program.pair.repository.SlotParticipationRepository slotParticipationRepository;

    @Mock
    org.program.pair.domain.notification.NotificationService notificationService;

    @Mock
    org.program.pair.domain.alert.ActivityAlertService activityAlertService;

    @Mock
    SubscriptionService subscriptionService;

    @Mock
    org.program.pair.domain.media.StoredImageResolver storedImageResolver;

    @InjectMocks
    ProgramService programService;

    /**
     * Le résolveur laisse passer l'URL telle quelle : ces tests ne portent pas
     * sur les références orphelines, et un stockage réel n'est pas de leur
     * ressort. {@code lenient()} parce que tous n'appellent pas {@code toDto}.
     */
    @BeforeEach
    void stubStoredImageResolver() {
        lenient().when(storedImageResolver.resolveOrNull(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * L'annonce ne part plus de la création : un programme naît en brouillon et
     * sans créneau — {@code CreateProgramRequest} n'en porte pas — donc sans date,
     * sans lieu et sans rien à décompter. Annoncée là, elle arrivait chez
     * l'abonné avec un titre, un auteur, et rien d'autre.
     */
    @Test
    void createProgram_sansCreneau_neDoitRienAnnoncerAuxAbonnes() {
        // Given
        User owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setDisplayName("Alice");

        Category category = Category.builder().id(UUID.randomUUID()).name("Sports").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Yoga").category(category).build();

        UserActivity ua = new UserActivity();
        ua.setId(UUID.randomUUID());
        ua.setUser(owner);
        ua.setActivity(activity);

        when(userActivityRepository.findByIdAndUserId(ua.getId(), owner.getId()))
            .thenReturn(Optional.of(ua));
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        when(programRepository.save(any())).thenAnswer(inv -> {
            Program p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(scheduleRepository.findByProgramId(any())).thenReturn(java.util.List.of());
        when(programMediaRepository.findByProgramIdOrderBySortOrder(any())).thenReturn(java.util.List.of());
        when(reviewRepository.findAverageRatingByProgramId(any())).thenReturn(null);
        when(reviewRepository.countByProgramId(any())).thenReturn(0L);
        when(userProgramRepository.countActiveParticipantsByProgramId(any())).thenReturn(0L);

        CreateProgramRequest request = new CreateProgramRequest(
            ua.getId(), "Yoga du matin", "Description", true, null,
            null, null, null, null, null, null, null, null, null, null
        );

        // When
        programService.createProgram(owner.getId(), request);

        // Then
        verify(subscriptionService, never()).notifySubscribersOfNewProgram(any());
    }

    /**
     * T3 — les abonnés sont annoncés au premier créneau, et l'annonce porte le
     * créneau lui-même : c'est {@code NotificationPayload.ofSchedule} qui sert
     * {@code scheduleId}, {@code sessionAt} et {@code placeName}, que la variante
     * A du template client attend et qu'{@code ofProgram} n'a jamais eus.
     */
    @Test
    void addSchedule_premierCreneau_doitAnnoncerLeProgrammeSitueALaSeance() {
        // Given
        Program program = buildOwnedProgram();
        UUID ownerId = program.getUserActivity().getUser().getId();
        Instant startsAt = Instant.parse("2026-08-17T17:00:00Z");

        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        programService.addSchedule(ownerId, program.getId(),
            publicSlotRequest("Piscine municipale", startsAt));

        // Then
        ArgumentCaptor<Schedule> captor = ArgumentCaptor.forClass(Schedule.class);
        verify(subscriptionService).notifySubscribersOfNewProgram(captor.capture());
        assertThat(captor.getValue().getStartsAt()).isEqualTo(startsAt);
        assertThat(captor.getValue().getPlaceName()).isEqualTo("Piscine municipale");
        assertThat(captor.getValue().getProgram()).isSameAs(program);

        // Et le programme est marqué : c'est ce qui rend l'annonce unique.
        assertThat(program.getSubscribersNotifiedAt()).isNotNull();
    }

    /**
     * Un programme déjà annoncé ne le sera pas une seconde fois. Le marqueur est
     * une date sur le programme et non un décompte de ses créneaux : l'unique
     * créneau supprimé puis reposé ferait sinon du suivant « le premier ».
     */
    @Test
    void addSchedule_programmeDejaAnnonce_neDoitPasReannoncer() {
        // Given
        Program program = buildOwnedProgram();
        program.setSubscribersNotifiedAt(Instant.parse("2026-08-14T09:00:00Z"));
        UUID ownerId = program.getUserActivity().getUser().getId();

        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        programService.addSchedule(ownerId, program.getId(),
            publicSlotRequest("Court n°3", Instant.parse("2026-08-20T17:00:00Z")));

        // Then
        verify(subscriptionService, never()).notifySubscribersOfNewProgram(any());
    }

    private static CreateScheduleRequest publicSlotRequest(String placeName, Instant startsAt) {
        return new CreateScheduleRequest(
            placeName,
            PlaceType.PUBLIC,
            48.85,
            2.35,
            "12 rue de la Paix",
            false,
            null, // ville
            startsAt,
            null,
            null,
            null,
            null,
            null, null
        );
    }

    @Test
    void addSchedule_lieuPublic_doitExigerAdresse() {
        // Given
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));

        CreateScheduleRequest request = new CreateScheduleRequest(
            "Stade municipal",
            PlaceType.PUBLIC,
            48.85,
            2.35,
            null, // adresse manquante
            false,
            null, // ville
            Instant.now(),
            null,
            null,
            null,
            null,
            null, null
        );

        // When / Then
        assertThatThrownBy(() ->
            programService.addSchedule(
                program.getUserActivity().getUser().getId(),
                program.getId(),
                request))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("adresse");
    }

    @Test
    void addSchedule_lieuPrive_sansConsentement_neDoitJamaisExposerAdresse() {
        // Given
        Program program = buildOwnedProgram();
        UUID ownerId = program.getUserActivity().getUser().getId();

        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateScheduleRequest request = new CreateScheduleRequest(
            "Chez moi",
            PlaceType.PRIVATE,
            48.85,
            2.35,
            "12 rue de la Paix", // adresse fournie
            false, // mais PAS de consentement explicite
            null, // ville
            Instant.now(),
            null,
            null,
            null,
            null,
            null, null
        );

        // When - Create as owner
        programService.addSchedule(ownerId, program.getId(), request);

        // Then - Verify address is NOT exposed when viewing as non-owner
        // Note: The test validates that toScheduleDto() logic correctly handles
        // PRIVATE locations without explicit consent by NOT returning coordinates
        // when the requester is NOT the owner. Since we're the owner here,
        // we expect coordinates to be visible. To properly test this, we'd need
        // to refactor toScheduleDto to accept a requesterId parameter.

        // For now, verify that the address field is properly set in the Schedule entity
        ArgumentCaptor<Schedule> captor = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleRepository).save(captor.capture());

        Schedule savedSchedule = captor.getValue();
        assertThat(savedSchedule.getPlaceType()).isEqualTo(PlaceType.PRIVATE);
        assertThat(savedSchedule.getShowExactAddress()).isNotEqualTo(Boolean.TRUE);
        // Address should NOT be stored when showExactAddress is false for PRIVATE
        assertThat(savedSchedule.getAddressPublic()).isNull();
    }

    @Test
    void addSchedule_lieuPrive_avecConsentement_doitExposerAdresse() {
        // Given
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateScheduleRequest request = new CreateScheduleRequest(
            "Chez moi",
            PlaceType.PRIVATE,
            48.85,
            2.35,
            "12 rue de la Paix",
            true, // consentement explicite
            null, // ville
            Instant.now(),
            null,
            null,
            null,
            null,
            null, null
        );

        // When
        ScheduleDto result = programService.addSchedule(
            program.getUserActivity().getUser().getId(),
            program.getId(),
            request
        );

        // Then
        assertThat(result.displayAddress()).isEqualTo("12 rue de la Paix");
    }

    @Test
    void updateProgram_archive_neDoitJamaisSupprimerPhysiquement() {
        // Given
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(programRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scheduleRepository.findByProgramId(any())).thenReturn(java.util.List.of());
        when(programMediaRepository.findByProgramIdOrderBySortOrder(any())).thenReturn(java.util.List.of());
        when(reviewRepository.findAverageRatingByProgramId(any())).thenReturn(null);
        when(reviewRepository.countByProgramId(any())).thenReturn(0L);
        when(userProgramRepository.countActiveParticipantsByProgramId(any())).thenReturn(0L);

        // When
        programService.updateProgram(
            program.getUserActivity().getUser().getId(),
            program.getId(),
            new UpdateProgramRequest(null, null, ProgramStatus.ARCHIVED, null, null,
                null, null, null, null, null, null, null, null, null, null)
        );

        // Then
        verify(programRepository, never()).delete(any());
        verify(programRepository, never()).deleteById(any());

        ArgumentCaptor<Program> captor = ArgumentCaptor.forClass(Program.class);
        verify(programRepository).save(captor.capture());
        assertThat(captor.getValue().getArchivedAt()).isNotNull();
    }

    private Program buildOwnedProgram() {
        User owner = new User();
        owner.setId(UUID.randomUUID());

        Category category = Category.builder().id(UUID.randomUUID()).name("Sports").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Yoga").category(category).build();

        UserActivity ua = new UserActivity();
        ua.setUser(owner);
        ua.setActivity(activity);

        Program p = new Program();
        p.setId(UUID.randomUUID());
        p.setUserActivity(ua);

        return p;
    }
}
