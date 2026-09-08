package org.program.pair.domain.recap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.user.UserService;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.RecapParticipantConsentRepository;
import org.program.pair.repository.RecapVibeVoteRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotRecapRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.HasErrorCode;
import org.program.pair.shared.sanitizer.HtmlSanitizer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les règles métier de la carte-souvenir.
 *
 * <p>Ce que ces tests protègent tient en une phrase : <b>on ne parle que du
 * moment, et seulement si on y était</b>. Tout le reste — la fenêtre de sept
 * jours, le plafond de deux ambiances, le garde-fou de publication — découle
 * de là.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlotRecapServiceTest extends RecapTestFixtures {

    @Mock SlotRecapRepository recapRepository;
    @Mock RecapVibeVoteRepository vibeVoteRepository;
    @Mock RecapParticipantConsentRepository consentRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock SlotAudience slotAudience;

    SlotRecapService service;

    /** Ce que le service a annoncé au reste de l'application. */
    final List<Object> published = new java.util.ArrayList<>();

    /**
     * Les présences du décor, partagées par la garde et par le rendu.
     *
     * <p>Depuis que les lectures sont groupées, « untel était là » se dit à deux
     * endroits : {@code existsBy…} garde la contribution, et le lot de présences
     * alimente {@code canContribute}. Les tenir dans une seule liste empêche un
     * décor où l'on peut contribuer à une carte qui vous ignore.
     */
    final List<org.program.pair.domain.attendance.Attendance> presents = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        published.clear();
        presents.clear();
        service = new SlotRecapService(recapRepository, vibeVoteRepository, consentRepository,
            attendanceRepository, scheduleRepository, userRepository, userService, slotAudience,
            new HtmlSanitizer(), published::add);

        when(scheduleRepository.findById(any())).thenAnswer(i -> Optional.of(slotById(i.getArgument(0))));
        when(recapRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.getReferenceById(any())).thenAnswer(i -> user(i.getArgument(0)));
        when(userService.getPublicProfile(any(), any())).thenAnswer(i -> publicProfile(i.getArgument(0)));
        when(vibeVoteRepository.countByVibeForRecaps(any())).thenReturn(List.of());
        when(vibeVoteRepository.findVibesByRecapIdsAndUserId(any(), any())).thenReturn(List.of());
        when(consentRepository.findConsentingByRecapIds(any())).thenReturn(List.of());
        when(attendanceRepository.findPresentForOccurrences(any(), any())).thenReturn(presents);
        when(slotAudience.participantIds(any())).thenReturn(List.of());
    }

    @Test
    void unNonParticipant_nePeutPasVoterDAmbiance() {
        Schedule slot = endedSlot(2);
        UUID stranger = UUID.randomUUID();
        presenceIs(slot, stranger, false);

        assertThatThrownBy(() -> service.voteVibes(stranger, slot.getId(), List.of("RELAXED")))
            .isInstanceOf(ForbiddenException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_NOT_ATTENDEE));
    }

    @Test
    void unParticipantAbsent_nePeutPasContribuer() {
        Schedule slot = endedSlot(2);
        UUID absent = UUID.randomUUID();
        // Inscrit, présent à l'appel, mais was_present = false : la requête de
        // présence est la même que celle de la boucle de recommandation, et
        // elle ne connaît que les présences vraies.
        presenceIs(slot, absent, false);

        assertThatThrownBy(() -> service.setConsent(absent, slot.getId(), true))
            .isInstanceOf(ForbiddenException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_NOT_ATTENDEE));
    }

    @Test
    void laContribution_estRefuseeAuDelaDeSeptJours() {
        Schedule slot = endedSlot(8 * 24);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);

        assertThatThrownBy(() -> service.voteVibes(attendee, slot.getId(), List.of("RELAXED")))
            .isInstanceOf(ConflictException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_WINDOW_CLOSED));
    }

    @Test
    void laContribution_resteOuverteJusquAuSeptiemeJour() {
        Schedule slot = endedSlot(6 * 24);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);
        noRecapYet(slot);

        SlotRecapDto dto = service.voteVibes(attendee, slot.getId(), List.of("RELAXED"));

        assertThat(dto.canContribute()).isTrue();
        verify(vibeVoteRepository).save(any());
    }

    @Test
    void auMaximumDeuxAmbiances_parPersonneEtParCreneau() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);
        noRecapYet(slot);

        assertThatThrownBy(() -> service.voteVibes(attendee, slot.getId(),
                List.of("RELAXED", "FRIENDLY", "FOCUSED")))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_INVALID_VIBES));
    }

    @Test
    void uneAmbianceHorsVocabulaire_estRefuseeEtJamaisStockee() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);
        noRecapYet(slot);

        assertThatThrownBy(() -> service.voteVibes(attendee, slot.getId(), List.of("COMPETITIVE")))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_INVALID_VIBES));

        verify(vibeVoteRepository, never()).save(any());
    }

    @Test
    void laCarte_seCreeALaPremiereContribution_pasAvant() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);
        noRecapYet(slot);

        // Avant toute contribution, rien n'a été créé pour ce créneau.
        verify(recapRepository, never()).save(any());

        service.voteVibes(attendee, slot.getId(), List.of("GOOD_LAUGH"));

        ArgumentCaptor<SlotRecap> created = ArgumentCaptor.forClass(SlotRecap.class);
        verify(recapRepository, atLeastOnce()).save(created.capture());
        assertThat(created.getValue().getSchedule()).isEqualTo(slot);
        // Née privée : la publication est une décision de l'hôte, jamais un défaut.
        assertThat(created.getValue().getVisibility()).isEqualTo(RecapVisibility.PRIVATE);
    }

    @Test
    void seulLHote_peutModifierLaNote() {
        Schedule slot = endedSlot(2);
        UUID intruder = UUID.randomUUID();
        presenceIs(slot, intruder, true);

        assertThatThrownBy(() -> service.setHostNote(intruder, slot.getId(), "Beau moment"))
            .isInstanceOf(ForbiddenException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_NOT_HOST));
    }

    @Test
    void seulLHote_peutModifierLaVisibilite() {
        Schedule slot = endedSlot(2);
        UUID intruder = UUID.randomUUID();
        presenceIs(slot, intruder, true);

        assertThatThrownBy(() -> service.setVisibility(intruder, slot.getId(), "PUBLIC"))
            .isInstanceOf(ForbiddenException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_NOT_HOST));
    }

    @Test
    void publicationRefusee_siAucunParticipantNonHoteNAConfirme() {
        Schedule slot = endedSlot(2);
        UUID host = hostIdOf(slot);
        existingRecap(slot);
        when(attendanceRepository.existsByScheduleIdAndAttendedAtAndWasPresentTrueAndUserIdNot(slot.getId(), slot.getStartsAt(), host))
            .thenReturn(false);

        assertThatThrownBy(() -> service.setVisibility(host, slot.getId(), "PUBLIC"))
            .isInstanceOf(ConflictException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RECAP_NEEDS_ATTENDEE));
    }

    @Test
    void publicationAcceptee_desQuUnAutreParticipantAConfirme() {
        Schedule slot = endedSlot(2);
        UUID host = hostIdOf(slot);
        SlotRecap recap = existingRecap(slot);
        when(attendanceRepository.existsByScheduleIdAndAttendedAtAndWasPresentTrueAndUserIdNot(slot.getId(), slot.getStartsAt(), host))
            .thenReturn(true);

        SlotRecapDto dto = service.setVisibility(host, slot.getId(), "PUBLIC");

        assertThat(dto.visibility()).isEqualTo("PUBLIC");
        assertThat(recap.getPublishedAt()).isNotNull();
    }

    @Test
    void unParticipantSansConsentement_estCompteMaisJamaisNomme() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        SlotRecap recap = existingRecap(slot);
        presenceIs(slot, attendee, true);
        when(attendanceRepository.countPresentByOccurrence(slot.getId(), slot.getStartsAt())).thenReturn(4);
        when(attendanceRepository.findPresentForOccurrences(any(), any()))
            .thenReturn(List.of(presentAttendance(slot, attendee)));
        // Aucun consentement enregistré.
        when(consentRepository.findConsentingByRecapIds(any())).thenReturn(List.of());
        when(consentRepository.findByRecapIdAndUserId(any(), any())).thenReturn(Optional.empty());

        SlotRecapDto dto = service.setConsent(attendee, slot.getId(), false);

        assertThat(dto.attendeeCount()).isEqualTo(4);
        assertThat(dto.visibleAttendees()).isEmpty();
    }

    @Test
    void retirerSonConsentementApresPublication_leRetireDeLaCarte() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        SlotRecap recap = existingRecap(slot);
        recap.setVisibility(RecapVisibility.PUBLIC);
        recap.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));

        presenceIs(slot, attendee, true);
        Attendance attendance = presentAttendance(slot, attendee);
        when(attendanceRepository.findPresentForOccurrences(any(), any()))
            .thenReturn(List.of(attendance));
        when(attendanceRepository.countPresentByOccurrence(slot.getId(), slot.getStartsAt())).thenReturn(3);

        RecapParticipantConsent consent = new RecapParticipantConsent();
        consent.setRecapId(recap.getId());
        consent.setUserId(attendee);
        consent.setShowIdentity(true);
        when(consentRepository.findByRecapIdAndUserId(recap.getId(), attendee))
            .thenReturn(Optional.of(consent));
        when(consentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // Après retrait, plus personne ne consent.
        when(consentRepository.findConsentingByRecapIds(any())).thenReturn(List.of());

        SlotRecapDto dto = service.setConsent(attendee, slot.getId(), false);

        assertThat(consent.getShowIdentity()).isFalse();
        assertThat(dto.visibleAttendees()).isEmpty();
        // La carte reste publique : retirer son nom ne dépublie pas le moment.
        assertThat(dto.visibility()).isEqualTo("PUBLIC");
        assertThat(dto.attendeeCount()).isEqualTo(3);
    }

    @Test
    void unParticipantConsentant_estNommeSurLaCarte() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        SlotRecap recap = existingRecap(slot);

        presenceIs(slot, attendee, true);
        when(attendanceRepository.findPresentForOccurrences(any(), any()))
            .thenReturn(List.of(presentAttendance(slot, attendee)));
        when(consentRepository.findByRecapIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(consentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(consentRepository.findConsentingByRecapIds(any()))
            .thenReturn(List.<Object[]>of(new Object[]{recap.getId(), attendee}));

        SlotRecapDto dto = service.setConsent(attendee, slot.getId(), true);

        assertThat(dto.visibleAttendees()).extracting(UserPublicDto::id).containsExactly(attendee);
    }

    @Test
    void leMotDeLHote_estSanitizeAvantDEtreRendu() {
        Schedule slot = endedSlot(2);
        UUID host = hostIdOf(slot);
        SlotRecap recap = existingRecap(slot);

        SlotRecapDto dto = service.setHostNote(host, slot.getId(), "<script>alert(1)</script>Belle séance");

        assertThat(recap.getHostNote()).doesNotContain("<script>");
        assertThat(dto.hostNote()).contains("Belle séance");
    }

    // — décor —

    private void presenceIs(Schedule slot, UUID userId, boolean present) {
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(slot.getId(), userId, slot.getStartsAt()))
            .thenReturn(present);
        if (present) {
            presents.add(presentAttendance(slot, userId));
        }
    }

    private void noRecapYet(Schedule slot) {
        when(recapRepository.findByScheduleIdAndOccurrenceStart(slot.getId(), slot.getStartsAt())).thenReturn(Optional.empty());
    }

    private SlotRecap existingRecap(Schedule slot) {
        SlotRecap recap = recapFor(slot);
        when(recapRepository.findByScheduleIdAndOccurrenceStart(slot.getId(), slot.getStartsAt())).thenReturn(Optional.of(recap));
        return recap;
    }

    private static ErrorCode codeOf(Throwable ex) {
        return ((HasErrorCode) ex).getErrorCode();
    }

    // ————————————————————————— ce que la carte annonce —————————————————————————

    /**
     * La NAISSANCE de la carte est annoncée, et elle seule : c'est l'instant où
     * la séance entre dans {@code /recaps/mine}, donc l'instant où une affiche
     * devient calculable pour tous ceux qui y étaient.
     */
    @Test
    void laNaissanceDeLaCarte_estAnnoncee() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);
        when(recapRepository.findByScheduleIdAndOccurrenceStart(any(), any())).thenReturn(Optional.empty());

        service.voteVibes(attendee, slot.getId(), List.of("RELAXED"));

        assertThat(published).singleElement()
            .isInstanceOfSatisfying(SlotRecapOpenedEvent.class, event -> {
                assertThat(event.scheduleId()).isEqualTo(slot.getId());
                assertThat(event.occurrenceStart()).isEqualTo(slot.getStartsAt());
                assertThat(event.openedBy()).isEqualTo(attendee);
            });
    }

    /**
     * Une contribution de plus sur une carte déjà ouverte n'annonce rien : c'est
     * ce qui garantit « au plus une notification par séance et par personne »
     * sans avoir à tenir le moindre compteur.
     */
    @Test
    void uneContributionDePlus_nAnnonceRien() {
        Schedule slot = endedSlot(2);
        UUID attendee = UUID.randomUUID();
        presenceIs(slot, attendee, true);

        SlotRecap existante = new SlotRecap();
        existante.setId(UUID.randomUUID());
        existante.setSchedule(slot);
        existante.setOccurrenceStart(slot.getStartsAt());
        existante.setOccurrenceEnd(slot.getEndsAt());
        existante.setVisibility(RecapVisibility.PRIVATE);
        when(recapRepository.findByScheduleIdAndOccurrenceStart(any(), any()))
            .thenReturn(Optional.of(existante));

        service.voteVibes(attendee, slot.getId(), List.of("RELAXED"));

        assertThat(published).isEmpty();
    }

    /** Une contribution refusée n'annonce rien non plus. */
    @Test
    void uneContributionRefusee_nAnnonceRien() {
        Schedule slot = endedSlot(2);
        UUID stranger = UUID.randomUUID();
        presenceIs(slot, stranger, false);

        assertThatThrownBy(() -> service.voteVibes(stranger, slot.getId(), List.of("RELAXED")))
            .isInstanceOf(ForbiddenException.class);

        assertThat(published).isEmpty();
    }
}
