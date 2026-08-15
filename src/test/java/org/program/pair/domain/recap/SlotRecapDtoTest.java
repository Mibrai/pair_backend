package org.program.pair.domain.recap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.recap.dto.NextSlotDto;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.recap.dto.VibeCountDto;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.RecapParticipantConsentRepository;
import org.program.pair.repository.RecapVibeVoteRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotRecapRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.sanitizer.HtmlSanitizer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Ce que la carte rend, et jusqu'où.
 *
 * <p>Les trois plafonds sont vérifiés ici parce qu'ils sont au <b>serveur</b> :
 * appliqués seulement côté client, ils laisseraient passer sur le fil des
 * données que personne n'a accepté de publier. Et {@code nextSlot} est vérifié
 * parce que c'est le champ qui transforme un lecteur en participant — le seul
 * dont l'absence vide la carte de sa raison d'être.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlotRecapDtoTest extends RecapTestFixtures {

    @Mock SlotRecapRepository recapRepository;
    @Mock RecapVibeVoteRepository vibeVoteRepository;
    @Mock RecapParticipantConsentRepository consentRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock SlotAudience slotAudience;

    SlotRecapService service;

    @BeforeEach
    void setUp() {
        service = new SlotRecapService(recapRepository, vibeVoteRepository, consentRepository,
            attendanceRepository, scheduleRepository, userRepository, userService, slotAudience,
            new HtmlSanitizer());

        when(userService.getPublicProfile(any(), any())).thenAnswer(i -> publicProfile(i.getArgument(0)));
        when(vibeVoteRepository.countByVibe(any())).thenReturn(List.of());
        when(vibeVoteRepository.findVibesByRecapIdAndUserId(any(), any())).thenReturn(List.of());
        when(consentRepository.findConsentingUserIds(any())).thenReturn(List.of());
        when(attendanceRepository.findByScheduleIdAndAttendedAtAndWasPresentTrue(any(), any())).thenReturn(List.of());
        when(slotAudience.participantIds(any())).thenReturn(List.of());
    }

    @Test
    void nextSlot_estNul_siAucunCreneauFuturOuvertSurCeProgramme() {
        Schedule slot = publicRecapOn(endedSlot(2));
        when(scheduleRepository.findNextOpenSlot(eq(slot.getProgram().getId()), any()))
            .thenReturn(Optional.empty());

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        // Franchement nul : le client propose alors l'abonnement au programme
        // plutôt qu'un cul-de-sac.
        assertThat(dto.nextSlot()).isNull();
    }

    @Test
    void nextSlot_designeLaProchaineSeanceOuverteDuMemeProgramme() {
        Schedule slot = publicRecapOn(endedSlot(2));
        Schedule next = futureOpenSlot(slot.getProgram(), 48);
        when(scheduleRepository.findNextOpenSlot(eq(slot.getProgram().getId()), any()))
            .thenReturn(Optional.of(next));

        UUID reader = UUID.randomUUID();
        SlotRecapDto dto = service.get(slot.getId(), reader);

        assertThat(dto.nextSlot()).isNotNull()
            .extracting(NextSlotDto::scheduleId, NextSlotDto::participantCount,
                NextSlotDto::maxParticipants, NextSlotDto::alreadyJoined)
            .containsExactly(next.getId(), 2, 8, false);
    }

    @Test
    void nextSlot_saitQueJyAiDejaMaPlace() {
        Schedule slot = publicRecapOn(endedSlot(2));
        Schedule next = futureOpenSlot(slot.getProgram(), 48);
        when(scheduleRepository.findNextOpenSlot(eq(slot.getProgram().getId()), any()))
            .thenReturn(Optional.of(next));

        UUID reader = UUID.randomUUID();
        when(slotAudience.participantIds(next)).thenReturn(List.of(reader));

        assertThat(service.get(slot.getId(), reader).nextSlot().alreadyJoined()).isTrue();
    }

    @Test
    void topVibes_estLimiteATroisEtTrieParNombreDeVotesDecroissant() {
        Schedule slot = publicRecapOn(endedSlot(2));

        // La requête rend déjà les ambiances triées ; le service ne doit ni
        // retrier à l'envers, ni en laisser passer une quatrième.
        when(vibeVoteRepository.countByVibe(any())).thenReturn(List.of(
            new Object[]{SlotVibe.FRIENDLY, 7L},
            new Object[]{SlotVibe.RELAXED, 5L},
            new Object[]{SlotVibe.GOOD_LAUGH, 2L},
            new Object[]{SlotVibe.OUTDOORS, 1L}
        ));

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        assertThat(dto.topVibes()).hasSize(3)
            .extracting(VibeCountDto::vibe).containsExactly("FRIENDLY", "RELAXED", "GOOD_LAUGH");
        assertThat(dto.topVibes()).extracting(VibeCountDto::count).containsExactly(7, 5, 2);
    }

    @Test
    void photoUrls_estLimiteATrois() {
        Schedule slot = publicRecapOn(endedSlot(2));

        List<Attendance> withPhotos = List.of(
            sharedPhoto(slot, "un.jpg"),
            sharedPhoto(slot, "deux.jpg"),
            sharedPhoto(slot, "trois.jpg"),
            sharedPhoto(slot, "quatre.jpg"));
        when(attendanceRepository.findByScheduleIdAndAttendedAtAndWasPresentTrue(slot.getId(), slot.getStartsAt())).thenReturn(withPhotos);

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        assertThat(dto.photoUrls()).hasSize(3)
            .containsExactly("un.jpg", "deux.jpg", "trois.jpg");
    }

    @Test
    void laCarte_neRendAucunLibelleDAmbiance_seulementLaValeur() {
        Schedule slot = publicRecapOn(endedSlot(2));
        when(vibeVoteRepository.countByVibe(any()))
            .thenReturn(List.<Object[]>of(new Object[]{SlotVibe.BEGINNER_FRIENDLY, 3L}));

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        // Le client tient les libellés dans ses trois catalogues : renvoyer une
        // traduction ici en ferait une quatrième source, forcément en retard.
        assertThat(dto.topVibes()).singleElement()
            .extracting(VibeCountDto::vibe).isEqualTo("BEGINNER_FRIENDLY");
    }

    @Test
    void canContribute_estFaux_pourQuiNEtaitPasLa() {
        Schedule slot = publicRecapOn(endedSlot(2));
        UUID reader = UUID.randomUUID();
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(slot.getId(), reader, slot.getStartsAt()))
            .thenReturn(false);

        assertThat(service.get(slot.getId(), reader).canContribute()).isFalse();
    }

    @Test
    void canContribute_estFaux_uneFoisLaFenetreRefermee() {
        Schedule slot = publicRecapOn(endedSlot(8 * 24));
        UUID attendee = UUID.randomUUID();
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(slot.getId(), attendee, slot.getStartsAt()))
            .thenReturn(true);

        assertThat(service.get(slot.getId(), attendee).canContribute()).isFalse();
    }

    @Test
    void myVibes_rendCeQueJaiDejaVote() {
        Schedule slot = publicRecapOn(endedSlot(2));
        UUID attendee = UUID.randomUUID();
        when(vibeVoteRepository.findVibesByRecapIdAndUserId(any(), eq(attendee)))
            .thenReturn(List.of(SlotVibe.FOCUSED, SlotVibe.TECHNICAL));

        assertThat(service.get(slot.getId(), attendee).myVibes())
            .containsExactly("FOCUSED", "TECHNICAL");
    }

    @Test
    void laCarte_porteLeContexteDActiviteAttenduParLeClient() {
        Schedule slot = publicRecapOn(endedSlot(2));

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        assertThat(dto.programTitle()).isEqualTo("Footing du mardi");
        assertThat(dto.activityName()).isEqualTo("Course à pied");
        assertThat(dto.categoryName()).isEqualTo("Sport");
        assertThat(dto.categoryColorRamp()).isEqualTo("orange-red");
        assertThat(dto.slotStartedAt()).isEqualTo(slot.getStartsAt());
    }

    // — décor —

    private Schedule publicRecapOn(Schedule slot) {
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));
        return slot;
    }

    private Attendance sharedPhoto(Schedule slot, String url) {
        Attendance attendance = presentAttendance(slot, UUID.randomUUID());
        attendance.setMemoryPhotoUrl(url);
        attendance.setMemoryIsPublic(true);
        return attendance;
    }
}
