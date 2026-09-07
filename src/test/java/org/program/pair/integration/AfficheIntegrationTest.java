package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.affiche.dto.AfficheDto;
import org.program.pair.domain.affiche.dto.AfficheUpdateDto;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le module « affiche », interrogé en HTTP contre une vraie base.
 *
 * <p>Deux propriétés ne se démontrent nulle part ailleurs, et ce sont
 * exactement les deux demandes bloquantes du contrat :
 *
 * <ul>
 *   <li><b>publier sans être l'hôte</b> — la seule route de publication
 *       existante, {@code PATCH /api/slots/{id}/recap/visibility}, rend 403 à
 *       quiconque n'organise pas ; ici c'est un simple participant qui
 *       publie ;</li>
 *   <li><b>l'audience appliquée par le serveur</b> — la requête de lecture
 *       filtre sur un lien (l'abonnement) que le lecteur ne peut pas connaître,
 *       et seule une vraie base dit si elle filtre ce qu'elle prétend filtrer.
 *       Un test à mocks ne prouverait que l'intention.</li>
 * </ul>
 */
class AfficheIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private static final ParameterizedTypeReference<List<AfficheDto>> AFFICHE_LIST =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<AfficheUpdateDto>> UPDATE_LIST =
        new ParameterizedTypeReference<>() {};

    @Test
    void unSimpleParticipant_publieSonAffiche_laOuLaCarteSouvenirLuiEstRefusee() {
        Fixture f = endedSlot("aff-participant");
        confirmPresence(f.guestToken, f.scheduleId);

        // La publication de la carte-souvenir, elle, reste réservée à l'hôte.
        webTestClient.patch()
            .uri("/api/slots/{id}/recap/visibility", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.guestToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"visibility\":\"PUBLIC\"}")
            .exchange()
            .expectStatus().isForbidden();

        AfficheDto affiche = publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "EVERYONE");

        assertThat(affiche.scheduleId()).isEqualTo(f.scheduleId);
        assertThat(affiche.motif()).isEqualTo("PREMIERE_FOIS");
        assertThat(affiche.audience()).isEqualTo("EVERYONE");
        assertThat(affiche.slotStartedAt()).isEqualTo(f.livedStart);
        assertThat(affiche.featuredUntil())
            .as("sept jours après la FIN de la séance, que le client ne connaissait pas")
            .isEqualTo(f.livedEnd.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void sansPresenceConfirmee_lAfficheEstRefusee() {
        Fixture f = endedSlot("aff-absent");

        webTestClient.put()
            .uri("/api/affiches/{id}", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.guestToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"motif\":\"PREMIERE_FOIS\",\"audience\":\"EVERYONE\"}")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("AFFICHE_NOT_ATTENDEE");
    }

    /**
     * Le cœur de la demande 2 : l'audience n'est pas un réglage déclaratif. Le
     * même lecteur, avant et après son abonnement, sur la même affiche.
     */
    @Test
    void lAudienceEstAppliqueeParLeServeur_etNonRendueAFiltrer() {
        Fixture f = endedSlot("aff-audience");
        confirmPresence(f.guestToken, f.scheduleId);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "SUBSCRIBERS");

        UUID auteurId = userRepository.findByEmail("aff-audience-participant@pair.app")
            .orElseThrow().getId();
        String lecteur = registerAndLogin("aff-audience-lecteur@pair.app");

        assertThat(affichesOf(lecteur, auteurId))
            .as("un lecteur non abonné ne reçoit rien — pas même une liste à filtrer")
            .isEmpty();

        subscribeToAuthor(lecteur, auteurId);

        assertThat(affichesOf(lecteur, auteurId))
            .as("le même lecteur, une fois abonné, la voit")
            .extracting(AfficheDto::motif).containsExactly("PREMIERE_FOIS");

        // Resserrée sur personne : même un abonné n'a plus rien.
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "NOBODY");
        assertThat(affichesOf(lecteur, auteurId)).isEmpty();

        assertThat(affichesOf(f.guestToken, auteurId))
            .as("mais chez soi, une affiche muette se voit toujours")
            .hasSize(1);
    }

    @Test
    void lAnneauNeSAllumeQueChezQuiADroitDeVoir() {
        Fixture f = endedSlot("aff-anneau");
        confirmPresence(f.guestToken, f.scheduleId);

        UUID auteurId = userRepository.findByEmail("aff-anneau-participant@pair.app")
            .orElseThrow().getId();
        String abonne = registerAndLogin("aff-anneau-abonne@pair.app");
        String tiers = registerAndLogin("aff-anneau-tiers@pair.app");
        subscribeToAuthor(abonne, auteurId);

        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "SUBSCRIBERS");

        assertThat(updatesSince(abonne, avant))
            .extracting(AfficheUpdateDto::userId).contains(auteurId);
        assertThat(updatesSince(tiers, avant))
            .as("le signal lui-même fuiterait ce que l'affiche protège")
            .extracting(AfficheUpdateDto::userId).doesNotContain(auteurId);
        assertThat(updatesSince(f.guestToken, avant))
            .as("et personne n'a besoin d'un anneau sur son propre avatar")
            .extracting(AfficheUpdateDto::userId).doesNotContain(auteurId);

        assertThat(updatesSince(abonne, Instant.now().plus(1, ChronoUnit.MINUTES)))
            .as("since dans le futur : plus rien de neuf")
            .isEmpty();
    }

    @Test
    void publierDeuxFois_neLaisseQuUneAffiche_etDepublierLaRetire() {
        Fixture f = endedSlot("aff-idempotence");
        confirmPresence(f.guestToken, f.scheduleId);

        UUID auteurId = userRepository.findByEmail("aff-idempotence-participant@pair.app")
            .orElseThrow().getId();

        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "EVERYONE");
        publishAffiche(f.guestToken, f.scheduleId, "DIXIEME_SEANCE", "EVERYONE");

        assertThat(affichesOf(f.guestToken, auteurId))
            .extracting(AfficheDto::motif)
            .as("une seule ligne, le dernier motif")
            .containsExactly("DIXIEME_SEANCE");

        unpublish(f.guestToken, f.scheduleId);
        assertThat(affichesOf(f.guestToken, auteurId)).isEmpty();

        // Idempotent : rejouer le geste dans le vide n'est pas une erreur.
        unpublish(f.guestToken, f.scheduleId);
    }

    /**
     * Demande 3 : la carte-souvenir porte désormais la fin de la séance, et non
     * plus seulement son début. Le repli que le client aurait dû écrire —
     * {@code slotStartedAt + 7 j} — se trompait de la durée de la séance.
     */
    @Test
    void laCarteSouvenirPorteLaFinDeLaSeance() {
        Fixture f = endedSlot("aff-fin");
        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);

        SlotRecapDto card = webTestClient.patch()
            .uri("/api/slots/{id}/recap/note", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.hostToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"note\":\"Belle séance.\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(SlotRecapDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(card).isNotNull();
        assertThat(card.slotEndedAt()).isEqualTo(f.livedEnd);
        assertThat(card.slotEndedAt()).isAfter(card.slotStartedAt());
        assertThat(card.recapWindowClosesAt())
            .as("la fenêtre de contribution se compte depuis cette fin-là")
            .isEqualTo(card.slotEndedAt().plus(7, ChronoUnit.DAYS));
    }

    // ————————————————————————— décor —————————————————————————

    private record Fixture(UUID scheduleId, String hostToken, String guestToken,
                           Instant livedStart, Instant livedEnd) {}

    /** Un créneau terminé il y a deux heures, son hôte, et un participant inscrit. */
    private Fixture endedSlot(String prefix) {
        String hostEmail = prefix + "-hote@pair.app";
        String hostToken = registerAndLogin(hostEmail);
        User host = userRepository.findByEmail(hostEmail).orElseThrow();

        String guestEmail = prefix + "-participant@pair.app";
        String guestToken = registerAndLogin(guestEmail);
        User guest = userRepository.findByEmail(guestEmail).orElseThrow();

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Bloc du mardi " + prefix)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant livedStart = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant livedEnd = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Mur des Lilas")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("Rue des Lilas")
            .city("Lyon")
            .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
            .startsAt(livedStart)
            .endsAt(livedEnd)
            .status(SlotStatus.OPEN)
            .isOpenToPartners(true)
            .build());

        participationRepository.save(SlotParticipation.builder()
            .schedule(schedule)
            .user(guest)
            .status(ParticipationStatus.CONFIRMED)
            .build());

        return new Fixture(schedule.getId(), hostToken, guestToken, livedStart, livedEnd);
    }

    private void confirmPresence(String token, UUID scheduleId) {
        webTestClient.post()
            .uri("/api/attendances/{scheduleId}/confirm", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"wasPresent\":true}")
            .exchange()
            .expectStatus().isOk();
    }

    private AfficheDto publishAffiche(String token, UUID scheduleId, String motif, String audience) {
        return webTestClient.put()
            .uri("/api/affiches/{id}", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"motif\":\"" + motif + "\",\"audience\":\"" + audience + "\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(AfficheDto.class)
            .returnResult()
            .getResponseBody();
    }

    private void unpublish(String token, UUID scheduleId) {
        webTestClient.delete()
            .uri("/api/affiches/{id}", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isNoContent();
    }

    private List<AfficheDto> affichesOf(String token, UUID userId) {
        return webTestClient.get()
            .uri("/api/users/{id}/affiches", userId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AFFICHE_LIST)
            .returnResult()
            .getResponseBody();
    }

    private List<AfficheUpdateDto> updatesSince(String token, Instant since) {
        return webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/api/affiches/updates")
                .queryParam("since", since.toString())
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UPDATE_LIST)
            .returnResult()
            .getResponseBody();
    }

    private void subscribeToAuthor(String token, UUID authorId) {
        webTestClient.post()
            .uri("/api/users/{id}/subscription", authorId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().is2xxSuccessful();
    }

    private String registerAndLogin(String email) {
        org.program.pair.domain.auth.dto.RegisterRequest registerReq =
            new org.program.pair.domain.auth.dto.RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        org.program.pair.domain.auth.dto.LoginRequest loginReq =
            new org.program.pair.domain.auth.dto.LoginRequest(email, "Password123!");
        org.program.pair.domain.auth.dto.AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(org.program.pair.domain.auth.dto.AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
