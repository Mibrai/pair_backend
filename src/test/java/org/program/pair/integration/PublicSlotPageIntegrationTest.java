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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot B1 — page publique de créneau.
 *
 * <p>Cette page n'existe que pour l'aperçu qu'une messagerie en fabrique. Les
 * métadonnées ne sont donc pas un détail de présentation : ce sont elles qu'on
 * vérifie en premier, avec ce que la page ne doit pas laisser sortir.
 */
class PublicSlotPageIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;
    private static final String ADDRESS = "12 rue tres precise";

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — l'adresse publique —

    @Test
    void leJeton_doitEtreCreeALaPremiereDemande_puisRester() {
        // Pas de rétro-remplissage : un créneau que personne n'a partagé n'a pas
        // d'adresse publique, et celui qu'on partage en obtient une aussitôt.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);

        assertThat(tokenInDb(slotId)).isNull();

        PublicShareLinkDto first = shareLink(host, slotId);
        assertThat(first.token()).hasSize(22);
        assertThat(first.shortUrl()).isEqualTo("https://meetdo.fun/s/" + first.token());
        assertThat(first.shareable()).isTrue();

        // Redemander ne doit pas changer l'adresse : un lien déjà partagé
        // continuerait de pointer vers l'ancienne.
        assertThat(shareLink(host, slotId).token()).isEqualTo(first.token());
    }

    @Test
    void unTiers_neDoitPasPouvoirFabriquerLAdresse() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String stranger = registerAndLogin();

        webTestClient.get().uri("/api/slots/{id}/share-link", slotId)
            .headers(h -> h.setBearerAuth(stranger))
            .exchange().expectStatus().isNotFound();
    }

    // — les métadonnées d'aperçu —

    @Test
    void lesMetadonneesOpenGraph_doiventEtreCompletes_etConcretes() {
        String host = registerAndLogin();
        String token = shareLink(host, publishSlot(host)).token();

        String html = body("/s/" + token);

        assertThat(html).contains("property=\"og:title\"");
        assertThat(html).contains("property=\"og:description\"");
        assertThat(html).contains("property=\"og:url\"");
        assertThat(html).contains("name=\"twitter:card\"");

        // La description dit quoi, quand, où, avec combien de monde — une phrase
        // vague ne ferait pas ouvrir le lien.
        assertThat(html).contains("Parc de l&#39;Orangerie");
        assertThat(html).contains("Strasbourg");
        assertThat(html).containsPattern("· \\d+ inscrit");
        // L'adresse absolue, jamais relative : un robot d'aperçu ne suit pas de
        // chemin relatif.
        assertThat(html).contains("https://meetdo.fun/s/" + token);
    }

    @Test
    void laPage_neDoitPorterAucunIdentifiantInterne_niAdressePrivee() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        String html = body("/s/" + token);

        assertThat(html).doesNotContain(slotId.toString());
        assertThat(html).doesNotContain("@pair.app");
        assertThat(html).doesNotContain(String.valueOf(LAT));
    }

    @Test
    void lAdresseExacte_dUnLieuPrive_neDoitPasSortir() {
        // broadcastableAddress ne rend l'adresse que si elle est déjà diffusable.
        String host = registerAndLogin();
        UUID slotId = publishPrivateSlot(host);
        String token = shareLink(host, slotId).token();

        assertThat(body("/s/" + token)).doesNotContain(ADDRESS);
    }

    @Test
    void laPage_doitMontrerLePrenom_pasLeNomComplet() {
        String host = registerAndLogin("Camille Duchemin");
        String token = shareLink(host, publishSlot(host)).token();

        String html = body("/s/" + token);
        assertThat(html).contains("Camille").doesNotContain("Duchemin");
    }

    // — les raisons de ne rien montrer —

    @Test
    void unCreneauRetireDuPartage_doitDisparaitre() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        assertThat(status("/s/" + token)).isEqualTo(200);

        jdbcTemplate.update("UPDATE schedules SET is_publicly_shareable = false WHERE id = ?", slotId);

        assertThat(status("/s/" + token)).isEqualTo(404);
    }

    @Test
    void unProgrammeEnBrouillon_doitDisparaitre() {
        // Condition absente de la liste de la spécification : sans elle, la page
        // publique serait plus permissive que le fil.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        jdbcTemplate.update(
            "UPDATE programs SET status = 'DRAFT' WHERE id = (SELECT program_id FROM schedules WHERE id = ?)",
            slotId);

        assertThat(status("/s/" + token)).isEqualTo(404);
    }

    @Test
    void unJetonInconnu_doitRendreLaMemePage_quUnCreneauRetire() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();
        jdbcTemplate.update("UPDATE schedules SET is_publicly_shareable = false WHERE id = ?", slotId);

        assertThat(body("/s/" + token)).isEqualTo(body("/s/" + "b".repeat(22)));
    }

    @Test
    void leJson_doitEtreDisponible_etFerme() {
        String host = registerAndLogin();
        String token = shareLink(host, publishSlot(host)).token();

        webTestClient.get().uri("/public/slots/{token}", token)
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.programTitle").exists()
            .jsonPath("$.organizerGivenName").exists()
            // Ce que le DTO fermé n'expose pas
            .jsonPath("$.host").doesNotExist()
            .jsonPath("$.scheduleId").doesNotExist()
            .jsonPath("$.lat").doesNotExist();
    }

    // — liens universels —

    @Test
    void lAssociationApple_doitEtreServie_depuisQueLIdentifiantEstConnu() {
        // Valeur communiquée par l'équipe mobile le 2026-08-19, relevée dans leur
        // projet iOS. Servie en JSON et sans redirection : Apple n'accepte ni
        // l'un ni l'autre écart.
        assertThat(status("/.well-known/apple-app-site-association")).isEqualTo(200);
        assertThat(body("/.well-known/apple-app-site-association"))
            .contains("97727T64DH.com.meetdo.app")
            .contains("/s/*");
    }

    @Test
    void lAssociationAndroid_doitRester404_tantQueLEmpreinteManque() {
        // L'empreinte SHA-256 dépend d'une décision qui n'est pas prise —
        // signature locale ou Play App Signing, auquel cas c'est Google qui
        // détient le certificat. Publier une association inventée serait pire que
        // de n'en publier aucune : Apple et Google les mettent en cache
        // agressivement, et une association fausse mémorisée par un appareil est
        // plus longue à corriger qu'une association absente.
        assertThat(status("/.well-known/assetlinks.json")).isEqualTo(404);
    }

    @Test
    void leBouton_doitViserLApplication_etNonLaPageElleMeme() {
        // Il pointait vers l'adresse de cette même page, ce qui ne menait nulle
        // part : sans application il la rechargeait, et avec — une fois les liens
        // universels actifs — iOS n'ouvre pas l'application depuis un lien vers
        // le domaine où le navigateur se trouve déjà.
        String host = registerAndLogin();
        String token = shareLink(host, publishSlot(host)).token();

        assertThat(body("/s/" + token)).contains("meetdo://slot/" + token);
    }

    // — helpers —

    private String body(String uri) {
        return new String(webTestClient.get().uri(uri)
            .exchange().expectBody().returnResult().getResponseBody());
    }

    private int status(String uri) {
        return webTestClient.get().uri(uri)
            .exchange().returnResult(byte[].class).getStatus().value();
    }

    private String tokenInDb(UUID slotId) {
        return jdbcTemplate.queryForObject(
            "SELECT public_share_token FROM schedules WHERE id = ?", String.class, slotId);
    }

    private PublicShareLinkDto shareLink(String token, UUID slotId) {
        return webTestClient.get().uri("/api/slots/{id}/share-link", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(PublicShareLinkDto.class).returnResult().getResponseBody();
    }

    private UUID publishSlot(String token) {
        return publishSlot(token, PlaceType.PUBLIC);
    }

    private UUID publishPrivateSlot(String token) {
        return publishSlot(token, PlaceType.PRIVATE);
    }

    private UUID publishSlot(String token, PlaceType placeType) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", placeType, LAT, LNG,
                ADDRESS, null, "Strasbourg", null, "Venez comme vous etes", null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        return registerAndLogin("Organisateur Test");
    }

    private String registerAndLogin(String displayName) {
        String email = uniqueEmail("publicslot");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", displayName))
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
