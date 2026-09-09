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
import static org.mockito.Mockito.doReturn;
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
        assertThat(dto.activityName())
            .as("sans le nom de l'activité, deux affiches de même motif et même "
                + "catégorie sont deux carreaux identiques")
            .isEqualTo("Escalade");
        assertThat(dto.categoryColorRamp()).isEqualTo("slate-cobalt");
    }

    /**
     * La chaîne {@code Schedule → Program → UserActivity → Activity → Category}
     * est celle de la carte-souvenir, et un maillon absent ne doit pas faire
     * échouer la galerie entière pour une teinte manquante : les deux champs
     * tombent à nul, le reste de l'affiche est rendu.
     */
    @Test
    void uneChaineDActiviteIncomplete_neCasseNiLAfficheNiLaGalerie() {
        slot.getProgram().setUserActivity(null);

        AfficheDto dto = service.publish(present, slot.getId(), publish("PREMIERE_FOIS", "EVERYONE"));

        assertThat(dto.activityName()).isNull();
        assertThat(dto.categoryColorRamp()).isNull();
        assertThat(dto.motif()).isEqualTo("PREMIERE_FOIS");
        assertThat(dto.scheduleId()).isEqualTo(slot.getId());
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
        doReturn(List.of(published(AfficheAudience.NOBODY, "PREMIERE_FOIS", Instant.now())))
            .when(afficheRepository).findByUserIdOrderByPublishedAtDesc(present);

        assertThat(service.forUser(present, present))
            .extracting(AfficheDto::activityName, AfficheDto::categoryColorRamp)
            .as("la galerie compose sans demander la carte-souvenir de la séance")
            .containsExactly(org.assertj.core.api.Assertions.tuple("Escalade", "slate-cobalt"));
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
     * Un horodatage de requête native revient en {@link Timestamp} ou en
     * {@code OffsetDateTime} selon la version : les deux doivent se lire, sans
     * quoi l'anneau porterait une date fausse — ce qui ne se voit pas.
     *
     * <p>Les <b>deux</b> colonnes d'horodatage y passent, et elles ne disent pas
     * la même chose : la date de publication et le début de la séance. Une
     * conversion qui n'en couvrirait qu'une ferait dire à la bande qu'une affiche
     * publiée hier parle d'hier.
     */
    @Test
    void lesHorodatagesDUneRequeteNative_seLisentDansLesDeuxFormes() {
        UUID lecteur = UUID.randomUUID();
        UUID publieur = UUID.randomUUID();
        Instant quand = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant seance = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        doReturn(List.of(
            new Object[]{publieur, Timestamp.from(quand), "Camille", "https://cdn/camille.jpg",
                "PREMIERE_FOIS", Timestamp.from(seance), "Escalade", "red-orange"},
            new Object[]{lecteur, quand.atOffset(java.time.ZoneOffset.UTC), "Dominique", null,
                "PREMIERE_FOIS", seance.atOffset(java.time.ZoneOffset.UTC), "Escalade", "red-orange"}))
            .when(afficheRepository).findUpdatesSince(any(), any(), anyInt());

        List<AfficheUpdateDto> updates = service.updatesSince(lecteur, null);

        assertThat(updates).extracting(AfficheUpdateDto::latestPublishedAt)
            .containsExactly(quand, quand);
        assertThat(updates).extracting(AfficheUpdateDto::slotStartedAt)
            .as("la séance, et non la publication")
            .containsExactly(seance, seance);
    }

    /**
     * La bande d'affiches n'a pas de liste hôte pour apporter les visages : ce
     * qu'elle ne reçoit pas ici, elle ne peut le résoudre que par une requête par
     * personne — précisément ce que cette route existe pour éviter.
     */
    @Test
    void lAnneauRendLeNomEtLAvatar_pourQueLaBandePuisseDessiner() {
        UUID lecteur = UUID.randomUUID();
        UUID publieur = UUID.randomUUID();
        UUID sansPhoto = UUID.randomUUID();
        Instant quand = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        doReturn(List.of(
            new Object[]{publieur, Timestamp.from(quand), "Camille", "https://cdn/camille.jpg",
                "PREMIERE_FOIS", Timestamp.from(quand), "Escalade", "red-orange"},
            new Object[]{sansPhoto, Timestamp.from(quand), "Dominique", null,
                "PREMIERE_FOIS", Timestamp.from(quand), "Escalade", "red-orange"}))
            .when(afficheRepository).findUpdatesSince(any(), any(), anyInt());

        List<AfficheUpdateDto> updates = service.updatesSince(lecteur, null);

        assertThat(updates)
            .extracting(AfficheUpdateDto::userId, AfficheUpdateDto::displayName,
                AfficheUpdateDto::avatarUrl)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple(publieur, "Camille", "https://cdn/camille.jpg"),
                // Un avatar absent reste nul : le client a déjà son repli, et en
                // fabriquer un ici le lui imposerait.
                org.assertj.core.api.Assertions.tuple(sansPhoto, "Dominique", null));
    }

    /**
     * B14 : la bande reçoit de quoi <b>dessiner</b> l'affiche, et plus seulement
     * de quoi dessiner le visage.
     *
     * <p>Sans ces quatre champs, le client sait qui a publié et doit demander
     * quoi — un {@code GET /users/{id}/affiches} par visage. Le compte est borné
     * par le nombre de gens qui ont publié, donc ce n'est pas le N+1 que cette
     * route existe pour éviter ; mais à ~200 ms l'aller-retour, dix visages
     * valent deux secondes sur le chemin le plus chaud de l'application.
     *
     * <p>Les quatre viennent de la <b>même ligne</b> que la date, et c'est ce que
     * le {@code DISTINCT ON} de la requête garantit : un motif emprunté à une
     * publication et une date empruntée à une autre décriraient une affiche qui
     * n'existe pas.
     */
    @Test
    void laBandeRecoitLesChampsDAffichageDeLaDerniereAffiche() {
        UUID lecteur = UUID.randomUUID();
        UUID publieur = UUID.randomUUID();
        Instant publiee = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant seance = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        // Le témoin de type est nécessaire : List.of(Object[]) prendrait le
        // tableau pour la liste elle-même et rendrait huit lignes d'une colonne.
        doReturn(List.<Object[]>of(new Object[]{
            publieur, Timestamp.from(publiee), "Camille", "https://cdn/camille.jpg",
            "PREMIERE_CATEGORIE", Timestamp.from(seance), "Escalade", "red-orange"}))
            .when(afficheRepository).findUpdatesSince(any(), any(), anyInt());

        assertThat(service.updatesSince(lecteur, null))
            .singleElement()
            .satisfies(u -> {
                assertThat(u.motif()).isEqualTo("PREMIERE_CATEGORIE");
                assertThat(u.activityName()).isEqualTo("Escalade");
                assertThat(u.categoryColorRamp()).isEqualTo("red-orange");
                assertThat(u.slotStartedAt())
                    .as("la séance dont l'affiche parle")
                    .isEqualTo(seance);
                assertThat(u.latestPublishedAt())
                    .as("et la publication, qui est une autre date")
                    .isEqualTo(publiee);
            });
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

        // Une catégorie distinctive, et pas une valeur banale : c'est elle qui
        // porte la rampe rendue au contrat, et un « Sport / red-orange » du
        // référentiel se confondrait avec la valeur qu'une autre source
        // fournirait par accident.
        org.program.pair.domain.activity.Category category =
            new org.program.pair.domain.activity.Category();
        category.setId(UUID.randomUUID());
        category.setName("Verticalité");
        category.setColorRamp("slate-cobalt");

        org.program.pair.domain.activity.Activity activity = new org.program.pair.domain.activity.Activity();
        activity.setId(UUID.randomUUID());
        activity.setName("Escalade");
        activity.setCategory(category);

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
