package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotBoundsResponse;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/slots/bounds} — les créneaux d'un rectangle.
 *
 * <p>Le défaut d'origine, signalé le 2026-09-04 : dézoomé sur toute
 * l'Allemagne, l'onglet Activités montrait ses 102 programmes et celui des
 * créneaux presque rien. Non pas à cause d'un bug, mais parce que
 * {@code /slots/feed} répondait à une question rectangulaire par un disque
 * plafonné à 50 km — et affirmait ensuite qu'il n'y avait rien.
 *
 * <p>Le premier test reproduit exactement cela : deux créneaux à 450 km l'un de
 * l'autre, invisibles au fil depuis le centre du pays, rendus tous les deux par
 * le rectangle. Sans son contre-test au fil, il ne prouverait rien — il
 * passerait aussi bien si la nouvelle route ne faisait qu'élargir le disque.
 */
class SlotBoundsIntegrationTest extends AbstractIntegrationTest {

    // Le rectangle de la mesure du client : toute l'Allemagne.
    private static final double NORTH = 55.1, SOUTH = 47.2, EAST = 15.1, WEST = 5.8;

    // Le centre géographique, d'où le fil ne voyait aucun des deux créneaux.
    private static final double CENTER_LAT = 51.1, CENTER_LNG = 10.4;

    private static final double COLOGNE_LAT = 50.938, COLOGNE_LNG = 6.960;
    private static final double MUNICH_LAT = 48.137, MUNICH_LNG = 11.576;

    // Les tests qui affirment un compte exact travaillent chacun dans sa propre
    // zone déserte. Deux raisons, et les deux ont été constatées : la base de
    // test est semée de créneaux un peu partout en Europe, et les tests d'une
    // même classe partagent leur base — un créneau publié par le test d'à côté
    // suffit à faire mentir un « totalInBounds vaut 2 ». Chaque test vérifie en
    // outre que sa zone est vide avant de publier, sinon il échoue en le disant
    // plutôt qu'en s'en accommoder.
    private static final AtomicInteger ZONE_SUIVANTE = new AtomicInteger();

    @Autowired ActivityRepository activityRepository;

    @Test
    void unRectangleAlEchelleDunPays_doitRendreLesCreneauxDesDeuxBouts() {
        UUID cologne = publishSlot(registerAndLogin(), COLOGNE_LAT, COLOGNE_LNG);
        UUID munich = publishSlot(registerAndLogin(), MUNICH_LAT, MUNICH_LNG);
        String viewer = registerAndLogin();

        // Le défaut, tel qu'il était mesuré : depuis le centre du pays, le disque
        // de 50 km — le maximum que le contrat autorise — n'atteint ni l'un ni
        // l'autre. C'est la moitié du test, et la plus importante : sans elle,
        // celle du dessous passerait même si rien n'avait changé.
        assertThat(feedIds(viewer)).doesNotContain(cologne, munich);

        SlotBoundsResponse response = bounds(viewer, NORTH, SOUTH, EAST, WEST, null);

        assertThat(response.slots()).extracting(SlotFeedItemDto::scheduleId)
            .contains(cologne, munich);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void unCreneauHorsDuRectangle_neDoitPasRemonter() {
        // La contrepartie : une route qui rendrait tout passerait le test du
        // dessus sans rien filtrer du tout.
        UUID munich = publishSlot(registerAndLogin(), MUNICH_LAT, MUNICH_LNG);
        String viewer = registerAndLogin();

        // Un rectangle serré autour de Cologne seule.
        SlotBoundsResponse response = bounds(viewer, 51.2, 50.6, 7.3, 6.6, null);

        assertThat(response.slots()).extracting(SlotFeedItemDto::scheduleId)
            .doesNotContain(munich);
    }

    @Test
    void unLieuPriveNonPartage_neDoitPasApparaitreSurLaCarte_maisResteDansLeFil() {
        // L'asymétrie est délibérée, et c'est le test qui la tient. Dans le fil,
        // ce créneau remonte sans coordonnées : trouvable, pas situé. Sur la
        // carte, la question posée EST géographique — répondre « il est dans ce
        // rectangle » le situe, et zoomé assez près, le rectangle est l'adresse.
        Zone zone = zoneDeserte();
        String viewer = registerAndLogin();
        assertThat(bounds(viewer, zone, null).totalInBounds()).isZero();

        UUID prive = publishPrivateSlot(registerAndLogin(), zone.lat(), zone.lng());

        SlotFeedItemDto dansLeFil = feed(viewer, zone.lat(), zone.lng()).stream()
            .filter(s -> s.scheduleId().equals(prive))
            .findFirst().orElse(null);
        assertThat(dansLeFil).isNotNull();
        assertThat(dansLeFil.lat()).isNull();

        SlotBoundsResponse response = bounds(viewer, zone, null);

        assertThat(response.slots()).extracting(SlotFeedItemDto::scheduleId)
            .doesNotContain(prive);
        // Le compte non plus ne doit pas le connaître : annoncer « il y en a un
        // ici » situe déjà un créneau dans le rectangle.
        assertThat(response.totalInBounds()).isZero();
    }

    @Test
    void unLieuPriveNonPartage_doitApparaitrePourUnParticipantConfirme() {
        // La règle du serveur est celle de SlotAddressVisibility, entièrement :
        // s'arrêter à « PUBLIC ou adresse assumée » cacherait à quelqu'un un
        // créneau dont il connaît déjà l'adresse et où il est attendu.
        Zone zone = zoneDeserte();
        String participant = registerAndLogin();
        assertThat(bounds(participant, zone, null).totalInBounds()).isZero();

        UUID prive = publishPrivateSlot(registerAndLogin(), zone.lat(), zone.lng());
        join(participant, prive);

        SlotBoundsResponse response = bounds(participant, zone, null);

        assertThat(response.slots()).extracting(SlotFeedItemDto::scheduleId).contains(prive);
        assertThat(response.totalInBounds()).isEqualTo(1);
    }

    @Test
    void toutCreneauRendu_doitPorterDesCoordonnees() {
        // Ce que la règle ci-dessus garantit, et qu'aucune autre lecture de
        // SlotFeedItemDto ne garantit : un pin sans position n'est pas un pin.
        publishSlot(registerAndLogin(), COLOGNE_LAT, COLOGNE_LNG);
        publishPrivateSlot(registerAndLogin(), MUNICH_LAT, MUNICH_LNG);

        SlotBoundsResponse response = bounds(registerAndLogin(), NORTH, SOUTH, EAST, WEST, null);

        assertThat(response.slots()).isNotEmpty();
        assertThat(response.slots()).allSatisfy(slot -> {
            assertThat(slot.lat()).isNotNull();
            assertThat(slot.lng()).isNotNull();
        });
    }

    @Test
    void laDistance_doitEtreNulle_fauteDeCentre() {
        // Sans centre il n'y a pas de distance à mesurer, et la mesurer depuis le
        // centre du rectangle rendrait un nombre que personne n'a demandé et que
        // rien n'oblige à être stable d'un geste de zoom à l'autre.
        publishSlot(registerAndLogin(), COLOGNE_LAT, COLOGNE_LNG);

        SlotBoundsResponse response = bounds(registerAndLogin(), NORTH, SOUTH, EAST, WEST, null);

        assertThat(response.slots()).isNotEmpty();
        assertThat(response.slots()).allSatisfy(s -> assertThat(s.distanceMeters()).isNull());
    }

    @Test
    void laTroncature_doitSeDire_etLeTotalDoitEtreExact() {
        // Le point de la demande : « nous préférons une carte qui dit qu'il y en
        // a plus à une carte qui en cache en silence ».
        Zone zone = zoneDeserte();
        String viewer = registerAndLogin();
        assertThat(bounds(viewer, zone, null).totalInBounds()).isZero();

        publishSlot(registerAndLogin(), zone.lat(), zone.lng());
        publishSlot(registerAndLogin(), zone.lat() + 0.05, zone.lng() - 0.05);

        SlotBoundsResponse response = bounds(viewer, zone, 1);

        assertThat(response.slots()).hasSize(1);
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalInBounds()).isEqualTo(2);
    }

    @Test
    void unLimitHorsBornes_doitEtreRefuse_pasEcreteEnSilence() {
        // Tout ce lot est né d'une borne rabotée sans le dire. Une route qui
        // ramènerait 500 à 200 en silence rejouerait exactement le défaut, un
        // étage plus bas.
        webTestClient.get()
            .uri(b -> b.path("/api/slots/bounds")
                .queryParam("north", NORTH).queryParam("south", SOUTH)
                .queryParam("east", EAST).queryParam("west", WEST)
                .queryParam("limit", 500).build())
            .headers(h -> h.setBearerAuth(registerAndLogin()))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void unRectangleInverse_doitEtreRefuseCommeSurMapBounds() {
        // Mêmes paramètres que /map/bounds veut dire mêmes refus : un contrat
        // qui ressemble à un autre sans se comporter comme lui est pire que deux
        // contrats différents.
        webTestClient.get()
            .uri(b -> b.path("/api/slots/bounds")
                .queryParam("north", SOUTH).queryParam("south", NORTH)
                .queryParam("east", EAST).queryParam("west", WEST).build())
            .headers(h -> h.setBearerAuth(registerAndLogin()))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MAP_BOUNDS_INVALID");
    }

    @Test
    void mesPropresCreneaux_neDoiventPasRemonter_commeDansLeFil() {
        // Le fil les écarte : un créneau qu'on organise n'est pas un créneau
        // qu'on peut rejoindre. Les faire apparaître ici ouvrirait, au tap sur le
        // pin, une feuille d'inscription à son propre créneau.
        Zone zone = zoneDeserte();
        String host = registerAndLogin();
        assertThat(bounds(host, zone, null).totalInBounds()).isZero();

        UUID mien = publishSlot(host, zone.lat(), zone.lng());

        SlotBoundsResponse response = bounds(host, zone, null);

        assertThat(response.slots()).extracting(SlotFeedItemDto::scheduleId).doesNotContain(mien);
        // Et le compte doit être d'accord avec la page : c'est ce que garantit un
        // filtre en SQL, là où un post-filtrage laisserait totalInBounds annoncer
        // un créneau absent et truncated s'allumer sur du vide.
        assertThat(response.totalInBounds()).isZero();
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void leFiltreDeCategorie_doitPorterCommeSurLeFil() {
        // Les filtres serveur étaient la deuxième demande du lot : les perdre
        // ferait filtrer à l'arrivée ce que le client sait écarter au départ.
        UUID slotId = publishSlot(registerAndLogin(), COLOGNE_LAT, COLOGNE_LNG);
        String viewer = registerAndLogin();

        UUID inconnue = UUID.randomUUID();
        SlotBoundsResponse filtre = webTestClient.get()
            .uri(b -> b.path("/api/slots/bounds")
                .queryParam("north", NORTH).queryParam("south", SOUTH)
                .queryParam("east", EAST).queryParam("west", WEST)
                .queryParam("categoryIds", inconnue).build())
            .headers(h -> h.setBearerAuth(viewer))
            .exchange().expectStatus().isOk()
            .expectBody(SlotBoundsResponse.class).returnResult().getResponseBody();

        assertThat(filtre).isNotNull();
        assertThat(filtre.slots()).extracting(SlotFeedItemDto::scheduleId).doesNotContain(slotId);
        assertThat(filtre.totalInBounds()).isZero();

        // Sans filtre, il est bien là : sinon le test ci-dessus passerait pour une
        // route qui ne rend jamais rien.
        assertThat(bounds(viewer, NORTH, SOUTH, EAST, WEST, null).slots())
            .extracting(SlotFeedItemDto::scheduleId).contains(slotId);
    }

    @Test
    void unCreneauHorsFenetreTemporelle_neDoitPasRemonter() {
        // La fenêtre par défaut reste celle du fil — sept jours. Un écran de
        // carte qui veut un horizon plus large doit le demander ; il ne l'obtient
        // pas en dézoomant.
        UUID lointain = publishSlot(registerAndLogin(), COLOGNE_LAT, COLOGNE_LNG,
            Instant.now().plus(30, ChronoUnit.DAYS));
        String viewer = registerAndLogin();

        assertThat(bounds(viewer, NORTH, SOUTH, EAST, WEST, null).slots())
            .extracting(SlotFeedItemDto::scheduleId).doesNotContain(lointain);

        SlotBoundsResponse elargi = webTestClient.get()
            .uri(b -> b.path("/api/slots/bounds")
                .queryParam("north", NORTH).queryParam("south", SOUTH)
                .queryParam("east", EAST).queryParam("west", WEST)
                .queryParam("to", Instant.now().plus(60, ChronoUnit.DAYS)).build())
            .headers(h -> h.setBearerAuth(viewer))
            .exchange().expectStatus().isOk()
            .expectBody(SlotBoundsResponse.class).returnResult().getResponseBody();

        assertThat(elargi).isNotNull();
        assertThat(elargi.slots()).extracting(SlotFeedItemDto::scheduleId).contains(lointain);
    }

    // — helpers —

    private SlotBoundsResponse bounds(String token, double north, double south,
                                      double east, double west, Integer limit) {
        SlotBoundsResponse response = webTestClient.get()
            .uri(b -> {
                var builder = b.path("/api/slots/bounds")
                    .queryParam("north", north).queryParam("south", south)
                    .queryParam("east", east).queryParam("west", west);
                if (limit != null) {
                    builder = builder.queryParam("limit", limit);
                }
                return builder.build();
            })
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotBoundsResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    /** Un point désert et le rectangle qui l'encadre. */
    private record Zone(double lat, double lng,
                        double north, double south, double east, double west) {}

    /**
     * Une zone déserte que ce test est seul à occuper — un degré de longitude
     * plus loin que la précédente, au milieu de la mer du Groenland, où ni les
     * semis ni les autres tests ne publient.
     */
    private Zone zoneDeserte() {
        double lng = -45.0 - ZONE_SUIVANTE.getAndIncrement();
        return new Zone(62.0, lng, 62.2, 61.8, lng + 0.2, lng - 0.2);
    }

    private SlotBoundsResponse bounds(String token, Zone zone, Integer limit) {
        return bounds(token, zone.north(), zone.south(), zone.east(), zone.west(), limit);
    }

    /** Le fil, tel que la carte l'appelait : un disque au rayon maximum. */
    private List<SlotFeedItemDto> feed(String token, double lat, double lng) {
        return webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", lat).queryParam("lng", lng)
                .queryParam("radiusMeters", 50000).build())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
    }

    private List<UUID> feedIds(String token) {
        return feed(token, CENTER_LAT, CENTER_LNG).stream()
            .map(SlotFeedItemDto::scheduleId).toList();
    }

    private void join(String token, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated();
    }

    private UUID publishSlot(String token, double lat, double lng) {
        return publishSlot(token, lat, lng, Instant.now().plus(2, ChronoUnit.DAYS));
    }

    private UUID publishSlot(String token, double lat, double lng, Instant startsAt) {
        return publish(token, new QuickSlotRequest(
            activityRepository.findAll().get(0).getId(), startsAt, null,
            "Un lieu public", PlaceType.PUBLIC, lat, lng,
            "1 rue de la Carte", null, "Ville", 5, null, null, null));
    }

    /** Un lieu privé dont l'adresse exacte n'est pas partagée. */
    private UUID publishPrivateSlot(String token, double lat, double lng) {
        return publish(token, new QuickSlotRequest(
            activityRepository.findAll().get(0).getId(),
            Instant.now().plus(2, ChronoUnit.DAYS), null,
            "Chez moi", PlaceType.PRIVATE, lat, lng,
            "3 rue Privée", false, "Ville", 5, null, null, null));
    }

    private UUID publish(String token, QuickSlotRequest request) {
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("bounds");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Organisateur"))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
