package org.program.pair.domain.affiche;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.program.pair.domain.affiche.dto.AfficheDto;
import org.program.pair.domain.affiche.dto.AfficheRequests;
import org.program.pair.domain.affiche.dto.AfficheUpdateDto;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AfficheRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.HasErrorCode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les deux règles du module « affiche », et rien d'autre :
 *
 * <ul>
 *   <li><b>publier suppose d'avoir été là</b> — la présence, pas
 *       l'organisation ;</li>
 *   <li><b>l'audience est appliquée par le serveur</b> — un lecteur sans droit
 *       reçoit une liste vide, pas une liste à filtrer.</li>
 * </ul>
 *
 * <p>Tout le reste — l'idempotence, la séance nommée, la date qui allume
 * l'anneau — découle de là.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AfficheServiceTest {

    @Mock AfficheRepository afficheRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock UserRepository userRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock BlockFilterService blockFilterService;

    AfficheService service;

    Schedule slot;
    UUID present = UUID.randomUUID();
    Instant seance;

    @BeforeEach
    void setUp() {
        service = new AfficheService(afficheRepository, attendanceRepository, scheduleRepository,
            userRepository, subscriptionRepository, blockFilterService);

        slot = endedSlot(2);
        seance = slot.getStartsAt();

        when(scheduleRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(userRepository.getReferenceById(any())).thenAnswer(i -> activeUser(i.getArgument(0)));
        when(afficheRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(afficheRepository.findByUserIdAndScheduleIdAndOccurrenceStart(any(), any(), any()))
            .thenReturn(Optional.empty());
        presenceIs(present, true);
    }

    // ————————————————————————— publier suppose d'avoir été là —————————————————————————

    @Test
    void unNonPresent_nePeutPasPublierDAffiche() {
        UUID stranger = UUID.randomUUID();
        presenceIs(stranger, false);

        assertThatThrownBy(() -> service.publish(stranger, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE")))
            .isInstanceOf(ForbiddenException.class)
            .extracting(e -> ((HasErrorCode) e).getErrorCode())
            .isEqualTo(ErrorCode.AFFICHE_NOT_ATTENDEE);

        verify(afficheRepository, never()).save(any());
    }

    /**
     * L'hôte n'a aucun privilège ici, et c'est le point du module : la seule
     * route de publication existante était réservée à l'hôte, ce qui laissait le
     * simple participant sans aucun endroit où publier.
     */
    @Test
    void lHote_nEstPasPrivilegie_seulLaPresenceCompte() {
        UUID host = slot.getProgram().getUserActivity().getUser().getId();
        presenceIs(host, false);

        assertThatThrownBy(() -> service.publish(host, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE")))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unSimpleParticipant_publieSonAffiche() {
        AfficheDto dto = service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE"));

        assertThat(dto.scheduleId()).isEqualTo(slot.getId());
        assertThat(dto.slotStartedAt()).isEqualTo(seance);
        assertThat(dto.motif()).isEqualTo("PREMIERE_FOIS");
        assertThat(dto.audience()).isEqualTo("EVERYONE");
    }

    /**
     * Sur une série hebdomadaire, la séance visée se nomme — et une séance où
     * l'on n'était pas se refuse, même quand on était à une autre du même
     * créneau.
     */
    @Test
    void uneSeanceNommeeOuLOnNEtaitPas_seRefuse() {
        Instant autreSemaine = seance.minus(7, ChronoUnit.DAYS);
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(
            slot.getId(), present, autreSemaine)).thenReturn(false);

        assertThatThrownBy(() -> service.publish(present, slot.getId(),
                new AfficheRequests.PublishRequest("PREMIERE_FOIS", "EVERYONE", autreSemaine)))
            .isInstanceOf(ForbiddenException.class)
            .extracting(e -> ((HasErrorCode) e).getErrorCode())
            .isEqualTo(ErrorCode.AFFICHE_NOT_ATTENDEE);
    }

    @Test
    void sansSeanceNommee_cEstLaPresenceLaPlusRecenteQuiEstPrise() {
        AfficheDto dto = service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE"));

        assertThat(dto.slotStartedAt()).isEqualTo(seance);
    }

    // ————————————————————————— idempotence et audience —————————————————————————

    @Test
    void publierDeuxFois_neLaisseQuUneAffiche() {
        Affiche existante = published(AfficheAudience.EVERYONE, "PREMIERE_FOIS",
            Instant.now().minus(3, ChronoUnit.DAYS));
        when(afficheRepository.findByUserIdAndScheduleIdAndOccurrenceStart(present, slot.getId(), seance))
            .thenReturn(Optional.of(existante));

        service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE"));

        ArgumentCaptor<Affiche> saved = ArgumentCaptor.forClass(Affiche.class);
        verify(afficheRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existante);
    }

    @Test
    void audienceAbsente_vautPersonne() {
        AfficheDto dto = service.publish(present, slot.getId(),
            new AfficheRequests.PublishRequest("PREMIERE_FOIS", null, null));

        assertThat(dto.audience()).isEqualTo("NOBODY");
    }

    @Test
    void audienceInconnue_seRefuse_plutotQueDeRetomberSurLeDefaut() {
        assertThatThrownBy(() -> service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "FRIENDS")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((HasErrorCode) e).getErrorCode())
            .isEqualTo(ErrorCode.AFFICHE_INVALID_AUDIENCE);
    }

    @Test
    void motifMalForme_seRefuse() {
        assertThatThrownBy(() -> service.publish(present, slot.getId(),
                publish("<script>alert(1)</script>", "EVERYONE")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((HasErrorCode) e).getErrorCode())
            .isEqualTo(ErrorCode.AFFICHE_INVALID_MOTIF);

        assertThatThrownBy(() -> service.publish(present, slot.getId(), publish("  ", "EVERYONE")))
            .isInstanceOf(BusinessException.class);
    }

    // ————————————————————————— la date qui allume l'anneau —————————————————————————

    @Test
    void ouvrirLAudience_republie_doncRallumeLAnneau() {
        Instant vieux = Instant.now().minus(30, ChronoUnit.DAYS);
        Affiche muette = published(AfficheAudience.SUBSCRIBERS, "PREMIERE_FOIS", vieux);
        when(afficheRepository.findByUserIdAndScheduleIdAndOccurrenceStart(present, slot.getId(), seance))
            .thenReturn(Optional.of(muette));

        AfficheDto dto = service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE"));

        assertThat(dto.publishedAt()).isAfter(vieux);
    }

    @Test
    void changerDeMotif_neRepubliePas() {
        Instant vieux = Instant.now().minus(30, ChronoUnit.DAYS);
        Affiche existante = published(AfficheAudience.EVERYONE, "PREMIERE_FOIS", vieux);
        when(afficheRepository.findByUserIdAndScheduleIdAndOccurrenceStart(present, slot.getId(), seance))
            .thenReturn(Optional.of(existante));

        AfficheDto dto = service.publish(present, slot.getId(), publish("DIXIEME_SEANCE", "EVERYONE"));

        assertThat(dto.motif()).isEqualTo("DIXIEME_SEANCE");
        assertThat(dto.publishedAt()).isEqualTo(vieux);
    }

    @Test
    void resserrerLAudience_neRepubliePas() {
        Instant vieux = Instant.now().minus(30, ChronoUnit.DAYS);
        Affiche existante = published(AfficheAudience.EVERYONE, "PREMIERE_FOIS", vieux);
        when(afficheRepository.findByUserIdAndScheduleIdAndOccurrenceStart(present, slot.getId(), seance))
            .thenReturn(Optional.of(existante));

        AfficheDto dto = service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "SUBSCRIBERS"));

        assertThat(dto.publishedAt()).isEqualTo(vieux);
    }

    // ————————————————————————— la fenêtre de mise en avant —————————————————————————

    /**
     * Sept jours après la <b>fin</b> de la séance, jamais après son début : le
     * repli que le client aurait dû écrire — {@code slotStartedAt + 7 j} — se
     * trompe de la durée de la séance.
     */
    @Test
    void laMiseEnAvantSeCompteDepuisLaFinDeLaSeance() {
        AfficheDto dto = service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE"));

        assertThat(dto.featuredUntil()).isEqualTo(slot.getEndsAt().plus(7, ChronoUnit.DAYS));
        assertThat(dto.featuredUntil()).isNotEqualTo(dto.slotStartedAt().plus(7, ChronoUnit.DAYS));
    }

    // ————————————————————————— l'audience, appliquée ici —————————————————————————

    @Test
    void chezSoi_toutSeVoit_ycomprisCeQueNulNeVoit() {
        when(afficheRepository.findByUserIdOrderByPublishedAtDesc(present))
            .thenReturn(List.of(published(AfficheAudience.NOBODY, "PREMIERE_FOIS", Instant.now())));

        assertThat(service.forUser(present, present)).hasSize(1);
        verify(afficheRepository, never()).findByUserIdAndAudienceInOrderByPublishedAtDesc(any(), any());
    }

    @Test
    void unTiersNonAbonne_neVoitQueLesAffichesOuvertesATous() {
        UUID lecteur = UUID.randomUUID();
        when(userRepository.findById(present)).thenReturn(Optional.of(activeUser(present)));
        when(subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(lecteur, present)).thenReturn(false);

        service.forUser(present, lecteur);

        assertThat(capturedAudiences()).containsExactly(AfficheAudience.EVERYONE);
    }

    @Test
    void unAbonne_voitAussiCellesReserveesAuxAbonnes() {
        UUID lecteur = UUID.randomUUID();
        when(userRepository.findById(present)).thenReturn(Optional.of(activeUser(present)));
        when(subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(lecteur, present)).thenReturn(true);

        service.forUser(present, lecteur);

        assertThat(capturedAudiences())
            .containsExactlyInAnyOrder(AfficheAudience.EVERYONE, AfficheAudience.SUBSCRIBERS);
    }

    /** Jamais {@code NOBODY} chez quelqu'un d'autre, quel que soit le lien. */
    @Test
    void personneNeVoitJamaisLesAffichesRegleesSurPersonne() {
        UUID lecteur = UUID.randomUUID();
        when(userRepository.findById(present)).thenReturn(Optional.of(activeUser(present)));
        when(subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(lecteur, present)).thenReturn(true);

        service.forUser(present, lecteur);

        assertThat(capturedAudiences()).doesNotContain(AfficheAudience.NOBODY);
    }

    @Test
    void unBlocage_videLaListe_sansRienDemanderAuDepot() {
        UUID lecteur = UUID.randomUUID();
        when(userRepository.findById(present)).thenReturn(Optional.of(activeUser(present)));
        when(blockFilterService.blocked(lecteur, present)).thenReturn(true);

        assertThat(service.forUser(present, lecteur)).isEmpty();
        verify(afficheRepository, never()).findByUserIdAndAudienceInOrderByPublishedAtDesc(any(), any());
    }

    @Test
    void unCompteDesactive_videLaListe() {
        UUID lecteur = UUID.randomUUID();
        User inactif = activeUser(present);
        inactif.setIsActive(false);
        when(userRepository.findById(present)).thenReturn(Optional.of(inactif));

        assertThat(service.forUser(present, lecteur)).isEmpty();
    }

    // ————————————————————————— l'anneau —————————————————————————

    @Test
    void sansSince_laFenetreParDefautEstDeSeptJours() {
        UUID lecteur = UUID.randomUUID();
        when(afficheRepository.findUpdatesSince(any(), any(), anyInt())).thenReturn(List.of());

        service.updatesSince(lecteur, null);

        assertThat(capturedSince())
            .isCloseTo(Instant.now().minus(7, ChronoUnit.DAYS), within10Seconds());
    }

    @Test
    void unSinceTropAncien_estRameneATrenteJours() {
        UUID lecteur = UUID.randomUUID();
        when(afficheRepository.findUpdatesSince(any(), any(), anyInt())).thenReturn(List.of());

        service.updatesSince(lecteur, Instant.now().minus(400, ChronoUnit.DAYS));

        assertThat(capturedSince())
            .isCloseTo(Instant.now().minus(30, ChronoUnit.DAYS), within10Seconds());
    }

    @Test
    void unSinceRecent_estHonoreTelQuel() {
        UUID lecteur = UUID.randomUUID();
        Instant since = Instant.now().minus(3, ChronoUnit.HOURS);
        when(afficheRepository.findUpdatesSince(any(), any(), anyInt())).thenReturn(List.of());

        service.updatesSince(lecteur, since);

        assertThat(capturedSince()).isEqualTo(since);
    }

    /**
     * {@code MAX(published_at)} d'une requête native revient en
     * {@link Timestamp} ou en {@code OffsetDateTime} selon la version : les deux
     * doivent se lire, sans quoi l'anneau porterait une date fausse — ce qui ne
     * se voit pas.
     */
    @Test
    void lHorodatageDUneAgregationNative_seLitDansLesDeuxFormes() {
        UUID lecteur = UUID.randomUUID();
        UUID publieur = UUID.randomUUID();
        Instant quand = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        when(afficheRepository.findUpdatesSince(any(), any(), anyInt())).thenReturn(List.of(
            new Object[]{publieur, Timestamp.from(quand)},
            new Object[]{lecteur, quand.atOffset(java.time.ZoneOffset.UTC)}));

        List<AfficheUpdateDto> updates = service.updatesSince(lecteur, null);

        assertThat(updates).extracting(AfficheUpdateDto::latestPublishedAt)
            .containsExactly(quand, quand);
    }

    // ————————————————————————— dépublier —————————————————————————

    @Test
    void depublierCeQuiNExistePas_nEstPasUneErreur() {
        when(afficheRepository.findFirstByUserIdAndScheduleIdOrderByOccurrenceStartDesc(present, slot.getId()))
            .thenReturn(Optional.empty());

        service.unpublish(present, slot.getId(), null);

        verify(afficheRepository, never()).delete(any());
    }

    /** Retirer ce qu'on a publié ne redemande pas la présence : elle a pu bouger. */
    @Test
    void depublier_neVerifieAucunePresence() {
        Affiche affiche = published(AfficheAudience.EVERYONE, "PREMIERE_FOIS", Instant.now());
        when(afficheRepository.findFirstByUserIdAndScheduleIdOrderByOccurrenceStartDesc(present, slot.getId()))
            .thenReturn(Optional.of(affiche));
        presenceIs(present, false);

        service.unpublish(present, slot.getId(), null);

        verify(afficheRepository).delete(affiche);
    }

    // ————————————————————————— décor —————————————————————————

    private AfficheRequests.PublishRequest publish(String motif, String audience) {
        return new AfficheRequests.PublishRequest(motif, audience, null);
    }

    private void presenceIs(UUID userId, boolean wasPresent) {
        when(attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(
            eq(slot.getId()), eq(userId), any())).thenReturn(wasPresent);
        if (wasPresent) {
            Attendance attendance = new Attendance();
            attendance.setAttendedAt(seance);
            attendance.setWasPresent(true);
            when(attendanceRepository.findFirstByScheduleIdAndUserIdAndWasPresentTrueOrderByAttendedAtDesc(
                slot.getId(), userId)).thenReturn(Optional.of(attendance));
        } else {
            when(attendanceRepository.findFirstByScheduleIdAndUserIdAndWasPresentTrueOrderByAttendedAtDesc(
                slot.getId(), userId)).thenReturn(Optional.empty());
        }
    }

    private Affiche published(AfficheAudience audience, String motif, Instant publishedAt) {
        Affiche affiche = new Affiche();
        affiche.setId(UUID.randomUUID());
        affiche.setUser(activeUser(present));
        affiche.setSchedule(slot);
        affiche.setOccurrenceStart(seance);
        affiche.setOccurrenceEnd(slot.getEndsAt());
        affiche.setMotif(motif);
        affiche.setAudience(audience);
        affiche.setPublishedAt(publishedAt);
        return affiche;
    }

    @SuppressWarnings("unchecked")
    private List<AfficheAudience> capturedAudiences() {
        ArgumentCaptor<java.util.Collection<AfficheAudience>> captor =
            ArgumentCaptor.forClass(java.util.Collection.class);
        verify(afficheRepository).findByUserIdAndAudienceInOrderByPublishedAtDesc(any(), captor.capture());
        return List.copyOf(captor.getValue());
    }

    private Instant capturedSince() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(afficheRepository).findUpdatesSince(any(), captor.capture(), anyInt());
        return captor.getValue();
    }

    private static org.assertj.core.data.TemporalUnitOffset within10Seconds() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(10, ChronoUnit.SECONDS);
    }

    private static User activeUser(UUID id) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("Participant");
        user.setIsActive(true);
        return user;
    }

    /** Un créneau d'une heure, terminé il y a {@code hoursSinceEnd} heures. */
    private static Schedule endedSlot(int hoursSinceEnd) {
        Instant endsAt = Instant.now().minus(hoursSinceEnd, ChronoUnit.HOURS)
            .truncatedTo(ChronoUnit.MILLIS);

        User host = activeUser(UUID.randomUUID());

        org.program.pair.domain.activity.Activity activity = new org.program.pair.domain.activity.Activity();
        activity.setId(UUID.randomUUID());
        activity.setName("Escalade");

        org.program.pair.domain.activity.UserActivity userActivity =
            new org.program.pair.domain.activity.UserActivity();
        userActivity.setId(UUID.randomUUID());
        userActivity.setUser(host);
        userActivity.setActivity(activity);

        org.program.pair.domain.program.Program program = new org.program.pair.domain.program.Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Bloc du mardi");
        program.setUserActivity(userActivity);
        program.setIsPublic(true);

        Schedule slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        slot.setStartsAt(endsAt.minus(Duration.ofHours(1)));
        slot.setEndsAt(endsAt);
        slot.setStatus(SlotStatus.PAST);
        return slot;
    }
}
