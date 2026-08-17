package org.program.pair.domain.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * L'émission : qui reçoit quoi, une fois par fait.
 *
 * <p>Le fan-out n'était couvert par aucun test avant ce lot — ni ici, ni
 * ailleurs : {@code ActivityServiceTest} et {@code ProgramServiceTest} se
 * contentent de vérifier qu'il est <i>appelé</i>. Son comportement n'était donc
 * retenu par rien dans un sens comme dans l'autre.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionFanoutTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock UserActivityRepository userActivityRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock NotificationService notificationService;

    @InjectMocks SubscriptionService subscriptionService;

    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    // Paris (Châtelet) et Berlin (Mitte) — ~878 km d'écart.
    private static final double PARIS_LAT = 48.8566;
    private static final double PARIS_LNG = 2.3522;
    private static final double BERLIN_LAT = 52.5200;
    private static final double BERLIN_LNG = 13.4050;

    private User user(String nom) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setDisplayName(nom);
        return u;
    }

    private UserActivity activite(User auteur, String nom, Category categorie) {
        Activity referentiel = Activity.builder()
            .id(UUID.randomUUID()).name(nom).category(categorie).build();
        UserActivity ua = new UserActivity();
        ua.setId(UUID.randomUUID());
        ua.setUser(auteur);
        ua.setActivity(referentiel);
        return ua;
    }

    private Schedule creneau(UserActivity ua, LocationType type, double lat, double lng) {
        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Séance du dimanche");
        program.setUserActivity(ua);
        program.setLocationType(type);

        Schedule slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        slot.setStartsAt(Instant.parse("2026-09-01T09:00:00Z"));
        Point point = GEO.createPoint(new Coordinate(lng, lat));
        slot.setLocation(point);
        return slot;
    }

    private Subscription abonnement(User abonne, SubscriptionType type, SubscriptionLevel niveau) {
        return Subscription.builder()
            .id(UUID.randomUUID())
            .subscriber(abonne)
            .type(type)
            .level(niveau)
            .build();
    }

    // --- Déduplication ---

    /**
     * Le cas qui motive tout le lot : une publication, deux abonnements, une
     * seule notification — et c'est le lien le plus délibéré qui la porte.
     */
    @Test
    void abonneALAuteurEtALActivite_neDoitRecevoirQuUneAnnonce() {
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course", null);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        Subscription versAuteur = abonnement(abonne, SubscriptionType.AUTHOR, SubscriptionLevel.ALL);
        versAuteur.setTargetAuthor(auteur);
        Subscription versActivite = abonnement(abonne, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.ALL);
        versActivite.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetAuthorId(auteur.getId()))
            .thenReturn(List.of(versAuteur));
        when(subscriptionRepository.findByTargetUserActivityId(ua.getId()))
            .thenReturn(List.of(versActivite));

        subscriptionService.notifySubscribersOfNewProgram(slot);

        verify(notificationService, times(1))
            .notify(eq(abonne.getId()), eq(NotificationType.AUTHOR_NEW_PROGRAM), any());
        verify(notificationService, never())
            .notify(any(), eq(NotificationType.ACTIVITY_NEW_PROGRAM), any());
    }

    @Test
    void deuxAbonnesDistincts_doiventRecevoirChacunLeurAnnonce() {
        User auteur = user("Lena");
        User parAuteur = user("Bob");
        User parActivite = user("Ana");
        UserActivity ua = activite(auteur, "Course", null);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        Subscription a = abonnement(parAuteur, SubscriptionType.AUTHOR, SubscriptionLevel.ALL);
        a.setTargetAuthor(auteur);
        Subscription b = abonnement(parActivite, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.ALL);
        b.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetAuthorId(auteur.getId())).thenReturn(List.of(a));
        when(subscriptionRepository.findByTargetUserActivityId(ua.getId())).thenReturn(List.of(b));

        subscriptionService.notifySubscribersOfNewProgram(slot);

        verify(notificationService).notify(eq(parAuteur.getId()),
            eq(NotificationType.AUTHOR_NEW_PROGRAM), any());
        verify(notificationService).notify(eq(parActivite.getId()),
            eq(NotificationType.ACTIVITY_NEW_PROGRAM), any());
    }

    /**
     * <b>Le piège d'ordre.</b> Filtrer par niveau AVANT de dédupliquer, jamais
     * l'inverse : dédupliquer d'abord ferait gagner la branche auteur, qui se
     * tairait ensuite, et cette personne ne recevrait rien — alors qu'elle avait
     * explicitement demandé à suivre l'activité.
     */
    @Test
    void auteurEnSourdineMaisActiviteActive_doitRecevoirParLActivite() {
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course", null);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        Subscription versAuteur = abonnement(abonne, SubscriptionType.AUTHOR, SubscriptionLevel.MUTED);
        versAuteur.setTargetAuthor(auteur);
        Subscription versActivite = abonnement(abonne, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.ALL);
        versActivite.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetAuthorId(auteur.getId()))
            .thenReturn(List.of(versAuteur));
        when(subscriptionRepository.findByTargetUserActivityId(ua.getId()))
            .thenReturn(List.of(versActivite));

        subscriptionService.notifySubscribersOfNewProgram(slot);

        verify(notificationService, times(1))
            .notify(eq(abonne.getId()), eq(NotificationType.ACTIVITY_NEW_PROGRAM), any());
    }

    // --- Niveau ---

    @Test
    void abonnementEnSourdine_neDoitRienRecevoir() {
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course", null);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        Subscription mute = abonnement(abonne, SubscriptionType.AUTHOR, SubscriptionLevel.MUTED);
        mute.setTargetAuthor(auteur);

        when(subscriptionRepository.findByTargetAuthorId(auteur.getId())).thenReturn(List.of(mute));
        when(subscriptionRepository.findByTargetUserActivityId(ua.getId())).thenReturn(List.of());

        subscriptionService.notifySubscribersOfNewProgram(slot);

        verifyNoInteractions(notificationService);
    }

    /** NEW_ONLY retient les mises à jour, et elles seules. */
    @Test
    void niveauNewOnly_doitRetenirLaMiseAJourMaisPasLaCreation() {
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course", null);

        Subscription nouveautes = abonnement(abonne, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.NEW_ONLY);
        nouveautes.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetUserActivityId(ua.getId()))
            .thenReturn(List.of(nouveautes));

        subscriptionService.notifySubscribersOfUserActivityUpdate(ua);
        verifyNoInteractions(notificationService);

        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);
        when(subscriptionRepository.findByTargetAuthorId(auteur.getId())).thenReturn(List.of());

        subscriptionService.notifySubscribersOfNewProgram(slot);
        verify(notificationService).notify(eq(abonne.getId()),
            eq(NotificationType.ACTIVITY_NEW_PROGRAM), any());
    }

    @Test
    void niveauAll_doitRecevoirLaMiseAJour() {
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course", null);

        Subscription tout = abonnement(abonne, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.ALL);
        tout.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetUserActivityId(ua.getId())).thenReturn(List.of(tout));

        subscriptionService.notifySubscribersOfUserActivityUpdate(ua);

        verify(notificationService).notify(eq(abonne.getId()),
            eq(NotificationType.ACTIVITY_UPDATED), any());
    }

    // --- Portée géographique ---

    private Subscription abonnementCategorie(User abonne, Category categorie,
                                             Double lat, Double lng, Integer rayon) {
        Subscription sub = abonnement(abonne, SubscriptionType.CATEGORY, SubscriptionLevel.ALL);
        sub.setTargetCategory(categorie);
        sub.setLat(lat);
        sub.setLng(lng);
        sub.setRadiusMeters(rayon);
        return sub;
    }

    @Test
    void rayonParisien_doitEcarterUneActiviteBerlinoise() {
        Category categorie = Category.builder().id(UUID.randomUUID()).name("Yoga").build();
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Yoga doux", categorie);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, BERLIN_LAT, BERLIN_LNG);

        when(subscriptionRepository.findByTargetCategoryId(categorie.getId()))
            .thenReturn(List.of(abonnementCategorie(abonne, categorie, PARIS_LAT, PARIS_LNG, 20_000)));

        subscriptionService.notifyCategorySubscribersIfFirstLocatedSlot(slot);

        verifyNoInteractions(notificationService);
        // L'annonce a bien eu lieu, même sans destinataire : ne pas réessayer.
        assertThat(ua.getCategoryNotifiedAt()).isNotNull();
    }

    @Test
    void rayonParisien_doitRetenirUneActiviteParisienne() {
        Category categorie = Category.builder().id(UUID.randomUUID()).name("Yoga").build();
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Yoga doux", categorie);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, 48.8600, 2.3400);

        when(subscriptionRepository.findByTargetCategoryId(categorie.getId()))
            .thenReturn(List.of(abonnementCategorie(abonne, categorie, PARIS_LAT, PARIS_LNG, 20_000)));

        subscriptionService.notifyCategorySubscribersIfFirstLocatedSlot(slot);

        verify(notificationService).notify(eq(abonne.getId()),
            eq(NotificationType.CATEGORY_NEW_ACTIVITY), any());
    }

    /**
     * Une activité à distance notifie toujours : c'est déjà la règle de
     * l'Explorer, et un filtre qui écarte ce qui n'a pas de géographie n'est pas
     * un filtre, c'est une perte.
     */
    @Test
    void activiteADistance_doitNotifierMalgreLeRayon() {
        Category categorie = Category.builder().id(UUID.randomUUID()).name("Yoga").build();
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Yoga en ligne", categorie);
        Schedule slot = creneau(ua, LocationType.REMOTE, BERLIN_LAT, BERLIN_LNG);

        when(subscriptionRepository.findByTargetCategoryId(categorie.getId()))
            .thenReturn(List.of(abonnementCategorie(abonne, categorie, PARIS_LAT, PARIS_LNG, 20_000)));

        subscriptionService.notifyCategorySubscribersIfFirstLocatedSlot(slot);

        verify(notificationService).notify(eq(abonne.getId()),
            eq(NotificationType.CATEGORY_NEW_ACTIVITY), any());
    }

    /** Sans portée, le comportement d'avant : le monde entier. */
    @Test
    void abonnementSansPortee_doitNotifierOuQueCeSoit() {
        Category categorie = Category.builder().id(UUID.randomUUID()).name("Yoga").build();
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Yoga doux", categorie);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, BERLIN_LAT, BERLIN_LNG);

        when(subscriptionRepository.findByTargetCategoryId(categorie.getId()))
            .thenReturn(List.of(abonnementCategorie(abonne, categorie, null, null, null)));

        subscriptionService.notifyCategorySubscribersIfFirstLocatedSlot(slot);

        verify(notificationService).notify(eq(abonne.getId()),
            eq(NotificationType.CATEGORY_NEW_ACTIVITY), any());
    }

    /** Une activité déjà annoncée ne l'est pas deux fois. */
    @Test
    void activiteDejaAnnoncee_neDoitPasReNotifier() {
        Category categorie = Category.builder().id(UUID.randomUUID()).name("Yoga").build();
        UserActivity ua = activite(user("Lena"), "Yoga doux", categorie);
        ua.setCategoryNotifiedAt(Instant.parse("2026-08-01T10:00:00Z"));
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        subscriptionService.notifyCategorySubscribersIfFirstLocatedSlot(slot);

        verifyNoInteractions(notificationService);
        verifyNoInteractions(subscriptionRepository);
    }

    // --- Provenance ---

    @Test
    void lePayload_doitNommerLAbonnementQuiAGagne() {
        User auteur = user("Lena Müller");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course du dimanche", null);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        Subscription versAuteur = abonnement(abonne, SubscriptionType.AUTHOR, SubscriptionLevel.ALL);
        versAuteur.setTargetAuthor(auteur);
        Subscription versActivite = abonnement(abonne, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.ALL);
        versActivite.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetAuthorId(auteur.getId()))
            .thenReturn(List.of(versAuteur));
        when(subscriptionRepository.findByTargetUserActivityId(ua.getId()))
            .thenReturn(List.of(versActivite));

        subscriptionService.notifySubscribersOfNewProgram(slot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(abonne.getId()),
            eq(NotificationType.AUTHOR_NEW_PROGRAM), payload.capture());

        // C'est l'abonnement gagnant qui est nommé — celui que l'appui long
        // mettra en sourdine, et donc celui qui doit faire taire cet envoi.
        assertThat(payload.getValue())
            .containsEntry("subscriptionId", versAuteur.getId().toString())
            .containsEntry("subscriptionType", "AUTHOR")
            .containsEntry("subscriptionLabel", "Lena Müller");
    }

    @Test
    void lePayload_doitNommerLActiviteQuandCEstElleQuiMene() {
        User auteur = user("Lena");
        User abonne = user("Bob");
        UserActivity ua = activite(auteur, "Course du dimanche", null);
        Schedule slot = creneau(ua, LocationType.IN_PERSON, PARIS_LAT, PARIS_LNG);

        Subscription versActivite = abonnement(abonne, SubscriptionType.USER_ACTIVITY, SubscriptionLevel.ALL);
        versActivite.setTargetUserActivity(ua);

        when(subscriptionRepository.findByTargetAuthorId(auteur.getId())).thenReturn(List.of());
        when(subscriptionRepository.findByTargetUserActivityId(ua.getId()))
            .thenReturn(List.of(versActivite));

        subscriptionService.notifySubscribersOfNewProgram(slot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(any(),
            eq(NotificationType.ACTIVITY_NEW_PROGRAM), payload.capture());

        assertThat(payload.getValue())
            .containsEntry("subscriptionType", "USER_ACTIVITY")
            .containsEntry("subscriptionLabel", "Course du dimanche");
        // Le contexte du programme reste intact à côté de la provenance.
        assertThat(payload.getValue()).containsKey("programId");
    }
}
