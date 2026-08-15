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
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.RecapParticipantConsentRepository;
import org.program.pair.repository.RecapVibeVoteRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotRecapRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Ce que la carte laisse voir, et à qui.
 *
 * <p>Une carte-souvenir est le seul objet du produit qu'un inconnu peut lire
 * <i>a posteriori</i> : elle est donc l'endroit où une fuite de lieu ou
 * d'identité coûterait le plus cher. D'où la règle du {@code 404} plutôt que
 * du {@code 403} — un refus explicite révélerait qu'il y a quelque chose là où
 * le demandeur n'a rien à savoir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlotRecapVisibilityTest extends RecapTestFixtures {

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
    void getRecapPrivee_parUnNonParticipant_retourne404_pas403() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        UUID stranger = UUID.randomUUID();
        when(attendanceRepository.existsByScheduleIdAndUserId(slot.getId(), stranger)).thenReturn(false);

        assertThatThrownBy(() -> service.get(slot.getId(), stranger))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getRecapPrivee_parUnParticipant_estLisible() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        UUID attendee = UUID.randomUUID();
        when(slotAudience.participantIds(slot)).thenReturn(List.of(attendee));

        assertThat(service.get(slot.getId(), attendee).scheduleId()).isEqualTo(slot.getId());
    }

    @Test
    void uneCarteDontLHoteEstInactif_nApparaitJamais() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        hostOf(slot).setIsActive(false);

        // Même publique, même demandée par quelqu'un qui y était.
        UUID attendee = UUID.randomUUID();
        when(slotAudience.participantIds(slot)).thenReturn(List.of(attendee));

        assertThatThrownBy(() -> service.get(slot.getId(), attendee))
            .isInstanceOf(ResourceNotFoundException.class);

        when(recapRepository.findMine(attendee)).thenReturn(List.of(recap));
        assertThat(service.getMine(attendee)).isEmpty();
    }

    @Test
    void uneCarteDontLeProgrammeNEstPlusPublic_nEstPlusLisible() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        slot.getProgram().setIsPublic(false);

        assertThatThrownBy(() -> service.get(slot.getId(), UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void lAdresseExacte_nEstJamaisPresenteDansLaCarte() {
        Schedule slot = endedSlot(2);
        slot.setAddressPublic("12 rue des Lilas, 69006 Lyon");
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        // Le lieu se dit par son nom et sa ville, jamais par son adresse.
        assertThat(dto.placeName()).isEqualTo("Parc de la Tête d'Or");
        assertThat(dto.cityLabel()).isEqualTo("Lyon");
        assertThat(dto.toString()).doesNotContain("12 rue des Lilas");
    }

    @Test
    void aucuneCoordonnee_neFigureAuContratDeLaCarte() {
        // Un champ absent du contrat ne peut pas fuiter demain. La carte ne
        // porte donc ni lat, ni lng, ni adresse — délibérément, et c'est ce
        // que ce test verrouille. La règle de lieu, elle, reste celle de
        // SlotAddressVisibility pour tous les autres points d'entrée.
        List<String> componentNames = java.util.Arrays.stream(SlotRecapDto.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

        assertThat(componentNames)
            .doesNotContain("lat", "lng", "displayAddress", "addressPublic", "location");
    }

    @Test
    void aucunePhoto_dUnParticipantSansMemoryIsPublic_nEstIncluse() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        Attendance prive = presentAttendance(slot, UUID.randomUUID());
        prive.setMemoryPhotoUrl("/api/media/files/prive.jpg");
        prive.setMemoryIsPublic(false);

        Attendance partage = presentAttendance(slot, UUID.randomUUID());
        partage.setMemoryPhotoUrl("/api/media/files/partage.jpg");
        partage.setMemoryIsPublic(true);

        when(attendanceRepository.findByScheduleIdAndAttendedAtAndWasPresentTrue(slot.getId(), slot.getStartsAt()))
            .thenReturn(List.of(prive, partage));

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        assertThat(dto.photoUrls()).containsExactly("/api/media/files/partage.jpg");
    }

    @Test
    void unParticipantSansConsentement_nEstJamaisNommeALaLecture() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        recap.setAttendeeCount(5);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        when(attendanceRepository.findByScheduleIdAndAttendedAtAndWasPresentTrue(slot.getId(), slot.getStartsAt()))
            .thenReturn(List.of(presentAttendance(slot, UUID.randomUUID())));
        when(consentRepository.findConsentingUserIds(recap.getId())).thenReturn(List.of());

        SlotRecapDto dto = service.get(slot.getId(), UUID.randomUUID());

        assertThat(dto.attendeeCount()).isEqualTo(5);
        assertThat(dto.visibleAttendees()).isEmpty();
    }

    @Test
    void lHote_nApparaitPasDeuxFois_surSaPropreCarte() {
        Schedule slot = endedSlot(2);
        SlotRecap recap = recapFor(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of(recap));

        UUID hostId = hostIdOf(slot);
        when(attendanceRepository.findByScheduleIdAndAttendedAtAndWasPresentTrue(slot.getId(), slot.getStartsAt()))
            .thenReturn(List.of(presentAttendance(slot, hostId)));
        when(consentRepository.findConsentingUserIds(recap.getId())).thenReturn(List.of(hostId));

        SlotRecapDto dto = service.get(slot.getId(), hostId);

        assertThat(dto.host().id()).isEqualTo(hostId);
        assertThat(dto.visibleAttendees()).isEmpty();
    }

    @Test
    void uneCarteInexistante_retourne404() {
        Schedule slot = endedSlot(2);
        when(recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(slot.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.get(slot.getId(), UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
