package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.safety.dto.SafetyShareLinkDto;
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
 * Lot A4 — partage de sécurité.
 *
 * <p>Une page ouverte sans compte, à qui détient le lien. Ce qui s'y trouve
 * compte donc moins que ce qui ne doit pas s'y trouver : l'essentiel de ces
 * tests vérifie des absences.
 */
class SlotSafetyShareIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;
    private static final String ADDRESS = "12 rue tres precise de l Orangerie";

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — qui peut créer un lien —

    @Test
    void lOrganisateur_doitPouvoirCreerUnLien() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);

        SafetyShareLinkDto link = createShare(host, slotId);

        assertThat(link.token()).hasSize(22);
        assertThat(link.url()).startsWith("https://").endsWith(link.token());
        assertThat(link.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void unParticipantInscrit_doitPouvoirCreerUnLien() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);

        String participant = registerAndLogin();
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(participant))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();

        assertThat(createShare(participant, slotId).token()).hasSize(22);
    }

    @Test
    void unTiers_doitRecevoir404_jamais403() {
        // Un 403 dirait « ce créneau existe, mais ». C'est précisément ce qu'un
        // inconnu qui essaie des identifiants ne doit pas apprendre.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String stranger = registerAndLogin();

        webTestClient.post().uri("/api/slots/{id}/safety-share", slotId)
            .headers(h -> h.setBearerAuth(stranger))
            .exchange().expectStatus().isNotFound();
    }

    // — la page publique —

    @Test
    void laPage_doitEtreLisibleSansCompte() {
        String host = registerAndLogin();
        SafetyShareLinkDto link = createShare(host, publishSlot(host));

        webTestClient.get().uri("/public/safety/{token}", link.token())
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
    }

    @Test
    void laPage_neDoitJamaisPorterLAdresseExacte_niDIdentifiantInterne() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        SafetyShareLinkDto link = createShare(host, slotId);

        String html = new String(webTestClient.get()
            .uri("/public/safety/{token}", link.token())
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());

        // Ce qu'on affiche. L'apostrophe est échappée une fois, par Thymeleaf,
        // et une seule : le sanitiseur stocke du texte, pas du HTML.
        assertThat(html).contains("Parc de l&#39;Orangerie").contains("Strasbourg");

        // Ce qu'on n'affiche pas, et qui est la raison d'être d'un DTO fermé
        assertThat(html).doesNotContain(ADDRESS);
        assertThat(html).doesNotContain(slotId.toString());
        // « @ » seul ne dirait rien : la feuille de style en contient (@media).
        // C'est le domaine des comptes de test qu'on traque.
        assertThat(html).doesNotContain("@pair.app");
        assertThat(html).doesNotContain("latitude").doesNotContain(String.valueOf(LAT));
    }

    @Test
    void laPage_doitMontrerLePrenom_etPasLeNomComplet() {
        // Le nom affiché est déjà public dans l'application ; cette page le rend
        // lisible sur le web ouvert, d'où la réduction.
        String host = registerAndLogin("Camille Duchemin");
        SafetyShareLinkDto link = createShare(host, publishSlot(host));

        String html = new String(webTestClient.get()
            .uri("/public/safety/{token}", link.token())
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());

        assertThat(html).contains("Camille");
        assertThat(html).doesNotContain("Duchemin");
    }

    @Test
    void unJetonInconnu_etUnLienExpire_doiventRendreLaMemePage() {
        String host = registerAndLogin();
        SafetyShareLinkDto link = createShare(host, publishSlot(host));

        // On périme le lien en base, sans toucher au créneau.
        jdbcTemplate.update("UPDATE slot_safety_shares SET expires_at = ? WHERE share_token = ?",
            java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), link.token());

        String expired = body("/public/safety/" + link.token());
        String unknown = body("/public/safety/" + "a".repeat(22));

        assertThat(expired).isEqualTo(unknown);
        assertThat(expired).contains("n'est plus valable");
    }

    @Test
    void lEcheance_doitEtreFigee_sixHeuresApresLaFin() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        SafetyShareLinkDto link = createShare(host, slotId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT expires_at, occurrence_ends_at FROM slot_safety_shares WHERE share_token = ?",
            link.token());

        Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        Instant ends = ((java.sql.Timestamp) row.get("occurrence_ends_at")).toInstant();

        assertThat(expires).isEqualTo(ends.plus(6, ChronoUnit.HOURS));
    }

    // — helpers —

    private String body(String uri) {
        return new String(webTestClient.get().uri(uri)
            .exchange().expectBody().returnResult().getResponseBody());
    }

    private SafetyShareLinkDto createShare(String token, UUID slotId) {
        return webTestClient.post().uri("/api/slots/{id}/safety-share", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated()
            .expectBody(SafetyShareLinkDto.class).returnResult().getResponseBody();
    }

    private UUID publishSlot(String token) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                ADDRESS, null, "Strasbourg", null, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        return registerAndLogin("Organisateur Test");
    }

    private String registerAndLogin(String displayName) {
        String email = uniqueEmail("safety");
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
