package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.publicslot.dto.PublicShareLinkDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot B3 — export calendrier.
 *
 * <p>Un fichier .ics ne se reprend pas : il est importé, resynchronisé, parfois
 * partagé entre appareils. Ce qu'on y écrit compte donc moins que ce qu'on
 * s'interdit d'y écrire — et la ligne de partage passe entre le participant
 * nommé et l'inconnu qui a reçu un lien.
 */
class SlotCalendarIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;
    private static final String ADDRESS = "12 rue tres precise";

    @Autowired ActivityRepository activityRepository;

    @Test
    void leFichier_doitEtreServiCommeUnCalendrier() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PUBLIC);

        webTestClient.get().uri("/api/slots/{id}/calendar.ics", slotId)
            .headers(h -> h.setBearerAuth(host))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.valueOf("text/calendar"))
            .expectHeader().value("Content-Disposition", v -> assertThat(v).contains("attachment"));
    }

    @Test
    void lEvenement_doitPorterLEssentiel_etUnRappelDeDeuxHeures() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PUBLIC);

        String ics = calendar(host, slotId);

        assertThat(ics).contains("BEGIN:VCALENDAR").contains("BEGIN:VEVENT").contains("END:VCALENDAR");
        assertThat(ics).contains("SUMMARY");
        assertThat(ics).contains("DTSTART").contains("DTEND");
        assertThat(ics).contains("LOCATION:Parc de l'Orangerie");
        // Le rappel suit le créneau plutôt qu'une heure absolue : déplacé, il
        // sonnerait sinon pour un rendez-vous qui a changé d'heure.
        assertThat(ics).contains("BEGIN:VALARM").contains("TRIGGER:-PT2H");
        // Identifiant stable : réimporter met à jour au lieu de dédoubler.
        // Le domaine de l'UID reste meetdo.fun alors que les liens sont passés à
        // lien.meetdo.fun, et c'est voulu : un UID est une identité, pas une
        // adresse. Le faire suivre dupliquerait, dans les agendas de tout le
        // monde, chaque événement déjà importé.
        assertThat(ics).contains("UID:" + slotId + "@meetdo.fun");
    }

    @Test
    void unParticipantNomme_doitEmporterLAdresseExacte() {
        // Il la voit déjà dans l'application ; un agenda qui ne dit pas où aller
        // ne sert à rien.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PUBLIC);

        assertThat(calendar(host, slotId)).contains(ADDRESS);
    }

    @Test
    void laVersionPublique_neDoitJamaisPorterLAdresseDUnLieuPrive() {
        // Sans demandeur identifié, seule broadcastableAddress peut trancher, et
        // elle se tait pour un lieu privé non partagé.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PRIVATE);
        String token = shareToken(host, slotId);

        String ics = publicCalendar(token);

        assertThat(ics).contains("BEGIN:VEVENT");
        assertThat(ics).doesNotContain(ADDRESS);
        assertThat(ics).contains("Parc de l'Orangerie");
    }

    @Test
    void laVersionPublique_doitPorterLeLienDeLaPage() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PUBLIC);
        String token = shareToken(host, slotId);

        assertThat(publicCalendar(token)).contains("https://lien.meetdo.fun/s/" + token);
    }

    @Test
    void unCreneauNonPartage_neDoitPasRecevoirDeLienDansSonIcs() {
        // En fabriquer un ici rendrait partageable, à l'insu de l'organisateur,
        // un créneau que personne n'avait partagé.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PUBLIC);

        assertThat(calendar(host, slotId)).doesNotContain("/s/");
    }

    @Test
    void unTiers_neDoitPasObtenirLeFichier() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, PlaceType.PUBLIC);
        String stranger = registerAndLogin();

        webTestClient.get().uri("/api/slots/{id}/calendar.ics", slotId)
            .headers(h -> h.setBearerAuth(stranger))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void mesCreneaux_doiventTenirDansUnSeulFichier_etExclureLePasse() {
        String host = registerAndLogin();
        UUID first = publishSlot(host, PlaceType.PUBLIC, 2);
        UUID second = publishSlot(host, PlaceType.PUBLIC, 5);

        String ics = unfolded(webTestClient.get().uri("/api/slots/mine/calendar.ics")
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());

        assertThat(ics).contains("UID:" + first + "@meetdo.fun");
        assertThat(ics).contains("UID:" + second + "@meetdo.fun");
        // Un agenda se remplit vers l'avant.
        assertThat(ics).containsOnlyOnce("BEGIN:VCALENDAR");
    }

    // — helpers —

    /**
     * Déplie les lignes du fichier avant de l'inspecter.
     *
     * <p>La RFC 5545 impose de couper toute ligne dépassant 75 octets et de
     * reprendre la suivante par une espace. C'est correct, et c'est invisible
     * pour un agenda — mais une URL s'y retrouve coupée en deux, et une
     * assertion qui l'ignore accuse le fichier d'un défaut qu'il n'a pas.
     */
    private static String unfolded(byte[] body) {
        return new String(body).replace("\r\n ", "").replace("\n ", "");
    }

    private String calendar(String token, UUID slotId) {
        return unfolded(webTestClient.get().uri("/api/slots/{id}/calendar.ics", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());
    }

    private String publicCalendar(String token) {
        return unfolded(webTestClient.get().uri("/public/slots/{token}/calendar.ics", token)
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());
    }

    private String shareToken(String token, UUID slotId) {
        PublicShareLinkDto link = webTestClient.get().uri("/api/slots/{id}/share-link", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(PublicShareLinkDto.class).returnResult().getResponseBody();
        assertThat(link).isNotNull();
        return link.token();
    }

    private UUID publishSlot(String token, PlaceType placeType) {
        return publishSlot(token, placeType, 2);
    }

    private UUID publishSlot(String token, PlaceType placeType, int inDays) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(inDays, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", placeType, LAT, LNG,
                ADDRESS, null, "Strasbourg", null, "Venez comme vous etes", null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("ics");
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
