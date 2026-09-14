package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * « Tout couper » — demande mobile du 14/09/2026 (modules/tracabilite, P-MU-05
 * étapes 4 à 6). Le scénario est celui que l'app rejouera à deux comptes : tout
 * se voit, on coupe, plus rien ne se voit depuis l'autre compte.
 */
class ToutCouperIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Besançon.
    private static final double LAT = 47.2378;
    private static final double LNG = 6.0241;
    private static final String MARQUEUR =
        "https://www.google.com/maps/search/?api=1&query=47.237800,6.024100\n"
            + "[meetdo:pos v1 lat=47.237800 lng=6.024100 exp=2026-09-14T18:30:00.000Z]";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;

    @Test
    void toutCeQuiSeVoyait_neSeVoitPlus_etLaCoupureSeRejoue() throws Exception {
        Compte moi = compte("coupe-moi");
        Compte autre = compte("coupe-autre");
        UUID scheduleId = creneau(moi);

        // Une affiche pour tout le monde, une autre pour les abonnés.
        affiche(moi, scheduleId, Instant.now().minus(3, ChronoUnit.DAYS), "EVERYONE");
        affiche(moi, scheduleId, Instant.now().minus(10, ChronoUnit.DAYS), "SUBSCRIBERS");
        Instant publieeLe = jdbcTemplate.queryForObject(
            "SELECT MIN(published_at) FROM affiches WHERE user_id = ?", java.sql.Timestamp.class, moi.id())
            .toInstant();

        // Une veille active et une veille close il y a deux heures : leurs pages s'ouvrent.
        String jetonActif = veilleAvecLien(moi, scheduleId, null);
        String jetonClos = veilleAvecLien(moi, creneau(moi), Instant.now().minus(2, ChronoUnit.HOURS));

        // Un partage par la route /location, un autre en texte comme l'app l'envoie.
        UUID fil = fil(moi, autre);
        UUID pointId = partagerPosition(moi, fil);
        UUID texteId = envoyer(moi, fil, MARQUEUR);
        UUID ordinaireId = envoyer(moi, fil, "À tout à l'heure");

        // Présence : les trois réglages.
        jdbcTemplate.update("""
            UPDATE users SET location = ST_SetSRID(ST_MakePoint(?, ?), 4326),
                   location_public = TRUE, show_location = TRUE, show_on_map = TRUE
             WHERE id = ?""", LNG, LAT, moi.id());

        // — tout se voit —
        assertThat(affichesVues(autre, moi)).isEqualTo(1);
        webTestClient.get().uri("/public/watch/{t}", jetonActif).exchange().expectStatus().isOk();
        webTestClient.get().uri("/public/watch/{t}", jetonClos).exchange().expectStatus().isOk();
        assertThat(etat(moi)).containsOnly(Map.entry("AFFICHES", true), Map.entry("WATCH_LINKS", true),
            Map.entry("CHAT_LOCATION", true), Map.entry("MAP_PRESENCE", true), Map.entry("LIVE_STATUS", false));

        // — on coupe —
        JsonNode coupure = couper(moi);
        assertThat(coupure.get("cutAt").asText()).isNotBlank();
        List<String> canaux = new ArrayList<>();
        coupure.get("channels").forEach(c -> {
            canaux.add(c.get("channel").asText());
            assertThat(c.get("cut").asBoolean()).isTrue();
            c.fieldNames().forEachRemaining(champ -> assertThat(champ).isIn("channel", "cut"));
        });
        assertThat(canaux).containsExactly("AFFICHES", "WATCH_LINKS", "CHAT_LOCATION", "MAP_PRESENCE", "LIVE_STATUS");

        // — plus rien ne se voit depuis l'autre compte —
        assertThat(affichesVues(autre, moi)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT MIN(published_at) FROM affiches WHERE user_id = ?", java.sql.Timestamp.class, moi.id())
            .toInstant()).as("une fermeture ne republie pas").isEqualTo(publieeLe);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT value FROM user_preferences WHERE user_id = ? AND key = 'affiche.audience'",
            String.class, moi.id())).isEqualTo("nobody");

        webTestClient.get().uri("/public/watch/{t}", jetonActif).exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/public/watch/{t}", jetonClos).exchange().expectStatus().isNotFound();

        JsonNode messages = messages(autre, fil);
        for (JsonNode m : messages) {
            assertThat(m.path("locationLat").isNull() || m.path("locationLat").isMissingNode()).isTrue();
            if (m.get("id").asText().equals(texteId.toString())) {
                assertThat(m.get("content").asText()).doesNotContain("google.com/maps").doesNotContain("meetdo:pos");
            }
            if (m.get("id").asText().equals(ordinaireId.toString())) {
                assertThat(m.get("content").asText()).as("un message ordinaire n'est pas touché")
                    .isEqualTo("À tout à l'heure");
            }
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT location_expires_at IS NULL FROM messages WHERE id = ?", Boolean.class, pointId)).isTrue();

        assertThat(jdbcTemplate.queryForMap(
            "SELECT location_public, show_location, show_on_map FROM users WHERE id = ?", moi.id()))
            .containsValues(false, false, false).doesNotContainValue(true);

        JsonNode etatApres = lireEtat(moi);
        assertThat(etat(moi)).allSatisfy((canal, ouvert) -> assertThat(ouvert).as(canal).isFalse());
        assertThat(etatApres.get("recentCuts")).hasSize(1);

        // — la coupure se rejoue —
        JsonNode encore = couper(moi);
        encore.get("channels").forEach(c -> assertThat(c.get("cut").asBoolean()).isTrue());
        assertThat(lireEtat(moi).get("recentCuts")).hasSize(2);
    }

    @Test
    void unComptesansRienAOuvrir_recoitDeuxCentsEtToutCoupe() throws Exception {
        Compte vide = compte("coupe-vide");
        JsonNode coupure = couper(vide);
        coupure.get("channels").forEach(c -> assertThat(c.get("cut").asBoolean()).isTrue());

        webTestClient.post().uri("/api/users/me/visibility/cut-all").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/users/me/visibility").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void supprimerUnPartageDePosition_effaceSonPoint() throws Exception {
        Compte moi = compte("suppr-point");
        Compte autre = compte("suppr-point-autre");
        UUID fil = fil(moi, autre);
        UUID pointId = partagerPosition(moi, fil);

        webTestClient.delete().uri("/api/messages/{id}", pointId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().is2xxSuccessful();

        for (JsonNode m : messages(autre, fil)) {
            if (m.get("id").asText().equals(pointId.toString())) {
                assertThat(m.path("locationLat").isNull() || m.path("locationLat").isMissingNode()).isTrue();
            }
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT location_lat IS NULL AND location_expires_at IS NULL FROM messages WHERE id = ?",
            Boolean.class, pointId)).isTrue();
    }

    @Test
    void bloquer_metFinAuxPartagesDePositionDuFilDansLesDeuxSens() {
        Compte moi = compte("bloque-point");
        Compte autre = compte("bloque-point-autre");
        UUID fil = fil(moi, autre);
        UUID lePoint = partagerPosition(moi, fil);
        UUID leSien = partagerPosition(autre, fil);
        UUID sonTexte = envoyer(autre, fil, MARQUEUR);

        webTestClient.post().uri("/api/users/{id}/block", autre.id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().is2xxSuccessful();

        for (UUID id : List.of(lePoint, leSien)) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT location_expires_at IS NULL FROM messages WHERE id = ?", Boolean.class, id)).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT content FROM messages WHERE id = ?", String.class, sonTexte))
            .doesNotContain("meetdo:pos");
    }

    @Test
    void uneVeilleAuLienRevoque_neSertPlusSonJeton() {
        Compte moi = compte("veille-revoquee");
        UUID scheduleId = creneau(moi);
        String jeton = veilleAvecLien(moi, scheduleId, null);
        Watch veille = watchRepository.findByPublicToken(jeton).orElseThrow();

        webTestClient.post().uri("/api/watches/{id}/revoke-link", veille.getId())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/api/watches/{id}", veille.getId())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.watch.publicToken").doesNotExist()
            .jsonPath("$.watch.publicStatusUrl").doesNotExist()
            .jsonPath("$.watch.publicLinkRevokedAt").isNotEmpty();
    }

    // — décor —

    private record Compte(UUID id, String email, String token) {}

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        AuthResponse auth = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Coupe" + prefixe.length()))
            .exchange().expectStatus().isCreated()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        adresseVerifiee(email);
        return new Compte(auth.userId(), email, auth.accessToken());
    }

    private UUID creneau(Compte owner) {
        Instant debut = Instant.now().plus(2, ChronoUnit.HOURS);
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(activityRepository.findAll().get(0).getId(), debut,
                debut.plus(1, ChronoUnit.HOURS), "Promenade Micaud", PlaceType.PUBLIC, LAT, LNG,
                "Promenade Micaud, Besançon", null, "Besançon", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(body.get("scheduleId")));
    }

    private void affiche(Compte owner, UUID scheduleId, Instant debut, String audience) {
        jdbcTemplate.update("""
            INSERT INTO affiches (user_id, schedule_id, occurrence_start, occurrence_end, motif, audience, published_at)
            VALUES (?, ?, ?, ?, 'soleil', ?, now() - interval '1 day')""",
            owner.id(), scheduleId, java.sql.Timestamp.from(debut),
            java.sql.Timestamp.from(debut.plus(1, ChronoUnit.HOURS)), audience);
    }

    private int affichesVues(Compte lecteur, Compte auteur) throws Exception {
        String corps = webTestClient.get().uri("/api/users/{id}/affiches", auteur.id())
            .headers(h -> h.setBearerAuth(lecteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        return objectMapper.readTree(corps).size();
    }

    /** Une veille dont la page publique s'ouvre, close à {@code closeLe} si donné. */
    private String veilleAvecLien(Compte owner, UUID scheduleId, Instant closeLe) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "phone", uniqueMobile(), "email", uniqueEmail("proche")))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String consentement = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", consentement)
            .exchange().expectStatus().isOk();

        UUID watchId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        Watch veille = watchRepository.findById(watchId).orElseThrow();
        String jeton = UUID.randomUUID().toString().replace("-", "").substring(0, 22); // la colonne en tient 22
        veille.setPublicToken(jeton);
        if (closeLe != null) {
            veille.setState(WatchState.CLOSED);
            veille.setClosedAt(closeLe);
        }
        watchRepository.saveAndFlush(veille);
        return jeton;
    }

    private UUID fil(Compte initiateur, Compte autre) {
        Map<?, ?> conv = webTestClient.post().uri("/api/conversations")
            .headers(h -> h.setBearerAuth(initiateur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("targetUserId", autre.id().toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(conv.get("id")));
    }

    private UUID partagerPosition(Compte auteur, UUID fil) {
        Map<?, ?> m = webTestClient.post().uri("/api/conversations/{id}/location", fil)
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("lat", LAT, "lng", LNG))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(m.get("id")));
    }

    private UUID envoyer(Compte auteur, UUID fil, String contenu) {
        Map<?, ?> m = webTestClient.post().uri("/api/conversations/{id}/messages", fil)
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("conversationId", fil.toString(), "content", contenu))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(m.get("id")));
    }

    private JsonNode messages(Compte lecteur, UUID fil) throws Exception {
        String corps = webTestClient.get().uri("/api/conversations/{id}/messages", fil)
            .headers(h -> h.setBearerAuth(lecteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        return objectMapper.readTree(corps);
    }

    private JsonNode couper(Compte compte) throws Exception {
        String corps = webTestClient.post().uri("/api/users/me/visibility/cut-all")
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        return objectMapper.readTree(corps);
    }

    private JsonNode lireEtat(Compte compte) throws Exception {
        String corps = webTestClient.get().uri("/api/users/me/visibility")
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        return objectMapper.readTree(corps);
    }

    private Map<String, Boolean> etat(Compte compte) throws Exception {
        Map<String, Boolean> parCanal = new java.util.LinkedHashMap<>();
        lireEtat(compte).get("channels").forEach(c -> {
            c.fieldNames().forEachRemaining(champ -> assertThat(champ).isIn("channel", "open"));
            parCanal.put(c.get("channel").asText(), c.get("open").asBoolean());
        });
        return parCanal;
    }
}
