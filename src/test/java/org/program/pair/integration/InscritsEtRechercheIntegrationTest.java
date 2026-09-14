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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande mobile inscription du 14/09, questions (b), (c) et (d). La (a) — « je la
 * vois » par inscription — est dans {@code ArrivalTwoStepIntegrationTest}.
 */
class InscritsEtRechercheIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Rennes, que personne d'autre n'emploie.
    private static final double LAT = 48.1173;
    private static final double LNG = -1.6778;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — (b) introuvable d'abord, puis les 403 nommés —

    @Test
    void uneRelationBloquee_rendIntrouvable_lesDeuxListes_commeLaFiche() {
        Compte hote = compte("liste-hote", "Hote");
        Compte inscrit = compte("liste-inscrit", "Inscrit");
        UUID creneau = publier(hote);
        rejoindre(inscrit, creneau);

        bloquer(inscrit, hote);

        get(inscrit, "/api/slots/{id}", creneau).expectStatus().isNotFound();
        get(inscrit, "/api/slots/{id}/participants", creneau).expectStatus().isNotFound();
        get(inscrit, "/api/slots/{id}/co-participants", creneau).expectStatus().isNotFound();
    }

    @Test
    void unHoteAuCompteFerme_rendIntrouvable_laListeDesInscrits() {
        Compte hote = compte("ferme-hote", "Hote");
        Compte curieux = compte("ferme-curieux", "Curieux");
        UUID creneau = publier(hote);

        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", hote.id());

        get(curieux, "/api/slots/{id}/participants", creneau).expectStatus().isNotFound();
    }

    @Test
    void uneFicheLisible_garde_lesRefusNommes() {
        Compte hote = compte("nomme-hote", "Hote");
        Compte curieux = compte("nomme-curieux", "Curieux");
        UUID creneau = publier(hote);

        get(curieux, "/api/slots/{id}/participants", creneau)
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.code").isEqualTo("SLOT_PARTICIPANTS_HOST_ONLY");
        get(curieux, "/api/slots/{id}/co-participants", creneau)
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.code").isEqualTo("SLOT_PARTICIPANTS_ENROLLED_ONLY");
    }

    // — (c) la bio n'est cherchable que là où elle serait rendue —

    @Test
    void laBioDUnProfilPrive_neDoitPasEtreCherchable_maisLeNomSi() {
        String motDeBio = "bio" + court();
        String nom = "Prive" + court();
        Compte personne = compte("bio-privee", nom);
        Compte chercheur = compte("bio-chercheur", "Chercheur");
        rendreTrouvable(personne, "Je pratique le " + motDeBio + " le dimanche.");

        assertThat(trouves(chercheur, motDeBio)).contains(personne.id());

        privacy(personne, Map.of("profileVisibility", "PRIVATE"));

        assertThat(trouves(chercheur, motDeBio)).doesNotContain(personne.id());
        assertThat(trouves(chercheur, nom)).contains(personne.id());
    }

    @Test
    void laBioDUnProfilReserveAuxAbonnes_neDoitEtreCherchableQueParUnAbonne() {
        String motDeBio = "bio" + court();
        Compte personne = compte("bio-amis", "Amis" + court());
        Compte abonne = compte("bio-abonne", "Abonne");
        Compte inconnu = compte("bio-inconnu", "Inconnu");
        rendreTrouvable(personne, "Passionnée de " + motDeBio + ".");
        privacy(personne, Map.of("profileVisibility", "FRIENDS"));
        webTestClient.post().uri("/api/users/{id}/subscription", personne.id())
            .headers(h -> h.setBearerAuth(abonne.token()))
            .exchange().expectStatus().isCreated();

        assertThat(trouves(abonne, motDeBio)).contains(personne.id());
        assertThat(trouves(inconnu, motDeBio)).doesNotContain(personne.id());
    }

    // — (d) plus de position —

    @Test
    void unePositionEnvoyee_neFiltrePlus_etUnePersonneSansPositionRemonte() {
        String nom = "Sanspos" + court();
        Compte personne = compte("sans-position", nom);
        Compte chercheur = compte("pos-chercheur", "Chercheur");
        rendreTrouvable(personne, null);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT location IS NULL FROM users WHERE id = ?", Boolean.class, personne.id())).isTrue();

        List<UUID> sansPoint = trouves(chercheur, nom);
        List<UUID> avecPoint = webTestClient.get()
            .uri(b -> b.path("/api/users").queryParam("query", nom)
                .queryParam("latitude", 52.52).queryParam("longitude", 13.40).build())
            .headers(h -> h.setBearerAuth(chercheur.token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody()
            .get("content") instanceof List<?> l ? ids(l) : List.of();

        assertThat(sansPoint).contains(personne.id());
        assertThat(avecPoint).isEqualTo(sansPoint);
    }

    // — outils —

    private record Compte(UUID id, String token) {}

    private static String court() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void rendreTrouvable(Compte compte, String bio) {
        privacy(compte, Map.of("showOnMap", true));
        if (bio != null) {
            webTestClient.put().uri("/api/users/me")
                .headers(h -> h.setBearerAuth(compte.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("bio", bio))
                .exchange().expectStatus().isOk();
        }
    }

    private void privacy(Compte compte, Map<String, Object> champs) {
        webTestClient.put().uri("/api/users/me/privacy")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(champs)
            .exchange().expectStatus().isOk();
    }

    private List<UUID> trouves(Compte chercheur, String query) {
        Map<?, ?> page = webTestClient.get()
            .uri(b -> b.path("/api/users").queryParam("query", query).queryParam("size", 50).build())
            .headers(h -> h.setBearerAuth(chercheur.token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        return ids((List<?>) page.get("content"));
    }

    private static List<UUID> ids(List<?> contenu) {
        return contenu.stream().map(e -> UUID.fromString(String.valueOf(((Map<?, ?>) e).get("id")))).toList();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec get(
            Compte compte, String uri, UUID id) {
        return webTestClient.get().uri(uri, id).headers(h -> h.setBearerAuth(compte.token())).exchange();
    }

    private void bloquer(Compte qui, Compte cible) {
        webTestClient.post().uri("/api/users/{id}/block", cible.id())
            .headers(h -> h.setBearerAuth(qui.token()))
            .exchange().expectStatus().isNoContent();
    }

    private UUID publier(Compte hote) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Instant debut = Instant.now().plus(3, ChronoUnit.DAYS);
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(hote.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, debut, debut.plus(Duration.ofHours(2)),
                "Parc du Thabor", PlaceType.PUBLIC, LAT, LNG,
                "Place Saint-Melaine, Rennes", null, "Rennes", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private void rejoindre(Compte compte, UUID creneau) {
        webTestClient.post().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isCreated();
    }

    private Compte compte(String prefixe, String nom) {
        String email = uniqueEmail("inscrits-" + prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", nom))
            .exchange().expectStatus().isCreated();
        adresseVerifiee(email);
        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE LOWER(email) = LOWER(?)", UUID.class, email);
        return new Compte(id, auth.accessToken());
    }
}
