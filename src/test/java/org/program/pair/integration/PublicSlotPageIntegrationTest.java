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
import java.util.Map;
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
        assertThat(first.shortUrl()).isEqualTo("https://lien.meetdo.fun/s/" + first.token());
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
        // Le décompte passe désormais par un choix de catalogue : à zéro il dit
        // « Personne encore, soyez le premier » plutôt que « 0 inscrit », qui se
        // lit comme un aveu d'échec sur la seule page censée donner envie.
        assertThat(html).containsPattern("· (\\d+ inscrit|Personne encore)");
        // L'adresse absolue, jamais relative : un robot d'aperçu ne suit pas de
        // chemin relatif.
        assertThat(html).contains("https://lien.meetdo.fun/s/" + token);
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

    // — spécification des liens publics (2026-08-19) —

    @Test
    void unCreneauAnnule_neDoitPlusAvoirDePagePublique() {
        // Le défaut que la relecture de la spécification a trouvé. Ces règles ont
        // été écrites au lot B1, avant que le lot C3 n'introduise l'annulation
        // d'un créneau — et personne n'est revenu ici. Le lien partagé continuait
        // d'inviter du monde à une séance annulée, sans qu'aucune erreur ne se
        // produise nulle part.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        assertThat(status("/s/" + token)).isEqualTo(200);

        webTestClient.post().uri("/api/slots/{id}/cancel", slotId)
            .headers(h -> h.setBearerAuth(host))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("reason", "Pluie"))
            .exchange().expectStatus().is2xxSuccessful();

        assertThat(status("/s/" + token)).isEqualTo(404);
    }

    @Test
    void lApercu_doitToujoursPorterUneImage() {
        // Un aperçu sans visuel s'affiche dans une messagerie comme deux lignes
        // grises, à peu près indiscernables d'un lien mort — et cet aperçu est la
        // seule raison d'être de la page.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        // Avec photo : c'est elle. Le référentiel en donne une à l'activité, si
        // bien que le cas nu ne se produit pas spontanément — il faut le créer.
        assertThat(body("/s/" + token)).contains("/public/slots/" + token + "/image");

        String restore = stripImages(slotId);
        try {
            assertThat(body("/s/" + token))
                .contains("og:image")
                .contains("/public/slots/" + token + "/cover.png");
        } finally {
            jdbcTemplate.update("UPDATE activities SET image_url = ? WHERE id = ?",
                restore, activityRepository.findAll().get(0).getId());
        }
    }

    @Test
    void laVignetteDessinee_doitEtreUnPngValide() {
        // La route existe quelle que soit l'image du créneau : c'est un repli
        // pour l'aperçu, pas une variante de l'image.
        String host = registerAndLogin();
        String token = shareLink(host, publishSlot(host)).token();

        byte[] png = webTestClient.get().uri("/public/slots/{t}/cover.png", token)
            .exchange().expectStatus().isOk()
            .expectHeader().contentType(org.springframework.http.MediaType.IMAGE_PNG)
            .expectBody(byte[].class).returnResult().getResponseBody();

        assertThat(png).isNotNull();
        // La signature PNG, plutôt qu'une simple taille non nulle : un flux
        // tronqué ou une page d'erreur passeraient le second test.
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    @Test
    void unRobotDApercu_neDoitPasCompterCommeUneOuverture() {
        // Un seul lien collé dans un groupe déclenche plusieurs de ces visites,
        // avant que quiconque n'ait cliqué. Comptées brutes, elles diraient que le
        // partage fonctionne alors que personne n'a rien ouvert.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        get("/s/" + token, "WhatsApp/2.23.20.0");
        get("/s/" + token, "facebookexternalhit/1.1");
        assertThat(viewCount(slotId)).isZero();

        get("/s/" + token, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15");
        assertThat(pollViewCount(slotId, 1)).isEqualTo(1);
    }

    @Test
    void leJsonEtLaPageInterne_neDoiventPasCompterDouverture() {
        // Seule l'adresse courte est collée quelque part.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        get("/public/slots/" + token, "Mozilla/5.0");
        get("/public/slots/" + token + "/page", "Mozilla/5.0");

        assertThat(viewCount(slotId)).isZero();
    }

    @Test
    void refermerLePartage_doitEteindreLeLien_sansChangerLeJeton() {
        // Rouvrir doit rendre valides les liens déjà collés ailleurs : un jeton
        // neuf transformerait une pause en rupture définitive.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();

        assertThat(setShareable(host, slotId, false).shareable()).isFalse();
        assertThat(status("/s/" + token)).isEqualTo(404);

        PublicShareLinkDto reopened = setShareable(host, slotId, true);
        assertThat(reopened.token()).isEqualTo(token);
        assertThat(status("/s/" + token)).isEqualTo(200);
    }

    @Test
    void refermerLePartage_doitEtreReserveALorganisateur() {
        // 404 et non 403 : un refus nommé confirmerait que le créneau existe.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String intruder = registerAndLogin("Curieux");

        webTestClient.patch().uri("/api/slots/{id}/shareable", slotId)
            .headers(h -> h.setBearerAuth(intruder))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("isPubliclyShareable", false))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void lagenda_doitEtreJoignableDepuisLadresseCourte() {
        // Le lien « Ajouter à mon agenda » de la page est relatif à l'adresse
        // que le lecteur a sous les yeux.
        String host = registerAndLogin();
        String token = shareLink(host, publishSlot(host)).token();

        assertThat(body("/s/" + token)).contains("/s/" + token + "/calendar.ics");
        assertThat(status("/s/" + token + "/calendar.ics")).isEqualTo(200);
    }

    @Test
    void laPage_doitParlerLaLangueDemandee() {
        String host = registerAndLogin();
        String token = shareLink(host, publishSlot(host)).token();

        String german = webTestClient.get().uri("/s/" + token)
            .header("Accept-Language", "de")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(german).contains("In meetDo öffnen").contains("lang=\"de\"");

        String english = webTestClient.get().uri("/s/" + token)
            .header("Accept-Language", "en")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(english).contains("Open in meetDo");
    }

    @Test
    void laLangueDuCreneau_doitPrimerSurCelleDuNavigateur() {
        // C'est la langue dans laquelle la séance se tiendra, donc celle du
        // lecteur visé — mieux que l'en-tête d'un appareil qui n'appartient
        // peut-être pas à quelqu'un du coin.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String token = shareLink(host, slotId).token();
        jdbcTemplate.update("UPDATE schedules SET primary_language = 'de' WHERE id = ?", slotId);

        String page = webTestClient.get().uri("/s/" + token)
            .header("Accept-Language", "en")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(page).contains("In meetDo öffnen");
    }

    // — helpers —

    private PublicShareLinkDto setShareable(String token, UUID slotId, boolean shareable) {
        return webTestClient.patch().uri("/api/slots/{id}/shareable", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("isPubliclyShareable", shareable))
            .exchange().expectStatus().isOk()
            .expectBody(PublicShareLinkDto.class).returnResult().getResponseBody();
    }

    /**
     * Retire les images du créneau et rend celle de l'activité, pour la reposer.
     *
     * <p>L'activité est partagée par toute la classe : la laisser sans image
     * ferait dépendre les tests suivants de l'ordre de passage.
     */
    private String stripImages(UUID slotId) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        String previous = jdbcTemplate.queryForObject(
            "SELECT image_url FROM activities WHERE id = ?", String.class, activityId);
        jdbcTemplate.update("UPDATE activities SET image_url = NULL WHERE id = ?", activityId);
        jdbcTemplate.update("""
            UPDATE programs SET image_url = NULL
            WHERE id = (SELECT program_id FROM schedules WHERE id = ?)
            """, slotId);
        return previous;
    }

    private void get(String uri, String userAgent) {
        webTestClient.get().uri(uri)
            .header("User-Agent", userAgent)
            .exchange().expectStatus().isOk();
    }

    private int viewCount(UUID slotId) {
        return jdbcTemplate.queryForObject(
            "SELECT public_view_count FROM schedules WHERE id = ?", Integer.class, slotId);
    }

    /** countView est @Async : la réponse HTTP part avant l'écriture. */
    private int pollViewCount(UUID slotId, int expected) {
        int seen = 0;
        for (int attempt = 0; attempt < 50 && seen < expected; attempt++) {
            seen = viewCount(slotId);
            if (seen < expected) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return seen;
    }

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
