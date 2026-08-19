package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D2 — filtres d'accessibilité.
 *
 * <p>Le point qui distingue ce lot du précédent : ce filtre est
 * <b>restrictif</b>. Une langue non déclarée veut dire « on ne sait pas » et ne
 * fait pas exclure ; une étiquette d'accessibilité non déclarée veut dire « rien
 * ne permet de l'affirmer », et montrer quand même le créneau enverrait
 * quelqu'un vers un lieu dont personne n'a garanti l'accueil.
 */
class SlotAccessibilityIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void unCreneauSansEtiquette_doitEtreEcarte_desQuOnFiltre() {
        // L'inverse exact du filtre de langue, et c'est délibéré : le coût de
        // l'erreur n'est pas du même ordre dans les deux sens.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);

        assertThat(feedIds(registerAndLogin(), null)).contains(slotId);
        assertThat(feedIds(registerAndLogin(), "WHEELCHAIR_ACCESSIBLE")).doesNotContain(slotId);
    }

    @Test
    void unCreneauQuiDeclareLEtiquette_doitRemonter() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        tag(slotId, "WHEELCHAIR_ACCESSIBLE");

        assertThat(feedIds(registerAndLogin(), "WHEELCHAIR_ACCESSIBLE")).contains(slotId);
    }

    @Test
    void plusieursEtiquettes_doiventSeCumuler() {
        // Qui filtre « fauteuil » ET « sans alcool » a besoin des deux : rendre
        // un créneau qui n'en déclare qu'une serait rendre le mauvais créneau.
        String host = registerAndLogin();
        UUID partial = publishSlot(host);
        tag(partial, "WHEELCHAIR_ACCESSIBLE");

        UUID complete = publishSlot(registerAndLogin());
        tag(complete, "WHEELCHAIR_ACCESSIBLE");
        tag(complete, "NO_ALCOHOL");

        List<UUID> feed = feedIds(registerAndLogin(), "WHEELCHAIR_ACCESSIBLE,NO_ALCOHOL");

        assertThat(feed).contains(complete);
        assertThat(feed).doesNotContain(partial);
    }

    @Test
    void lesEtiquettes_doiventApparaitreSurLeCreneau() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        tag(slotId, "FAMILY_FRIENDLY");
        tag(slotId, "FREE_OF_CHARGE");

        SlotFeedItemDto slot = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();

        assertThat(slot.accessibilityTags())
            .containsExactly("FAMILY_FRIENDLY", "FREE_OF_CHARGE");
    }

    @Test
    void unCreneauSansEtiquette_doitRendreUneListeVide_jamaisNull() {
        // Le client affiche une rangée d'étiquettes : lui rendre null l'obligerait
        // à distinguer « aucune » de « pas renseigné », alors que c'est la même
        // chose ici.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);

        SlotFeedItemDto slot = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();

        assertThat(slot.accessibilityTags()).isEmpty();
    }

    @Test
    void uneEtiquetteInconnue_neDoitRienRemonter_sansPlanter() {
        // Une valeur qu'aucun créneau ne porte : la réponse est vide, pas une
        // erreur — le filtre compare des chaînes, il ne valide pas un vocabulaire.
        String host = registerAndLogin();
        publishSlot(host);

        assertThat(feedIds(registerAndLogin(), "CE_TAG_NEXISTE_PAS")).isEmpty();
    }

    // — helpers —

    private void tag(UUID slotId, String tag) {
        jdbcTemplate.update(
            "INSERT INTO schedule_accessibility_tags (schedule_id, tag) VALUES (?, ?)",
            slotId, tag);
    }

    private List<UUID> feedIds(String token, String tags) {
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> {
                var builder = b.path("/api/slots/feed")
                    .queryParam("lat", LAT).queryParam("lng", LNG)
                    .queryParam("radiusMeters", 20000);
                if (tags != null) {
                    builder = builder.queryParam("accessibilityTags", tags);
                }
                return builder.build();
            })
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return feed.stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    private UUID publishSlot(String token) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("a11y");
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
