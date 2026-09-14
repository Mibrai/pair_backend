package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demande mobile du 14/09/2026 (modules/badges, P-MU-25) — le catalogue servi ne
 * porte ni série, ni palier, ni note, ni chiffre dans un nom.
 *
 * <p>L'app garde son filtre des séries ; ce test est l'interrupteur côté serveur.
 * Il lit ce que la route rend, puis la graine de référence, parce que le seeder
 * repasse après les migrations à chaque démarrage : une ligne retirée par V122
 * mais laissée dans {@code badges.json} reviendrait au déploiement suivant.
 */
class BadgesSansSerieNiPalierIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void leCatalogueServi_neContientNiSerieNiNoteNiPalierNiChiffre() throws Exception {
        String token = inscrire();

        String corps = webTestClient.get().uri("/api/badges")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        JsonNode catalogue = objectMapper.readTree(corps);

        assertThat(catalogue).isNotEmpty();
        List<Map<String, Object>> lignes = new ArrayList<>();
        catalogue.forEach(b -> {
            assertThat(b.has("conditionThreshold"))
                .as("le seuil d'un badge est un compte, il n'est plus servi").isFalse();
            lignes.add(Map.of(
                "code", b.get("code").asText(),
                "name", b.get("name").asText(),
                "conditionType", b.get("conditionType").asText()));
        });
        verifierSansSerieNiPalier(lignes, "GET /api/badges");
    }

    @Test
    void laGraineDeReference_neReintroduitRien() throws Exception {
        List<Map<String, Object>> lignes = new ArrayList<>();
        try (InputStream in = new ClassPathResource("seed/data/badges.json").getInputStream()) {
            objectMapper.readTree(in).forEach(b -> lignes.add(Map.of(
                "code", b.get("code").asText(),
                "name", b.get("label").asText(),
                "conditionType", b.get("conditionType").asText())));
        }
        verifierSansSerieNiPalier(lignes, "seed/data/badges.json");
    }

    @Test
    void laBaseRefuseUnBadgeDeSerie() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon)
                VALUES (gen_random_uuid(), 'SERIE_REFUSEE_V122', 'ACHIEVEMENT', 'Série', 'WEEKLY_STREAK', 4, 'flame')
                """))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_badges_ni_serie_ni_note");
    }

    private void verifierSansSerieNiPalier(List<Map<String, Object>> badges, String source) {
        Map<String, Integer> parCondition = new HashMap<>();
        for (Map<String, Object> b : badges) {
            String type = (String) b.get("conditionType");
            assertThat(type).as("%s : %s est une série", source, b.get("code")).doesNotContain("STREAK");
            assertThat(type).as("%s : %s est une note", source, b.get("code"))
                .isNotIn("AVERAGE_REVIEW_SCORE", "PERFECT_REVIEWS");
            assertThat((String) b.get("name")).as("%s : chiffre dans le nom de %s", source, b.get("code"))
                .doesNotContainPattern("\\d");
            if (!type.equals("VERIFICATION") && !type.equals("MANUAL")) {
                parCondition.merge(type, 1, Integer::sum);
            }
        }
        assertThat(parCondition).as("%s : un seul badge par condition, sinon c'est un palier", source)
            .allSatisfy((type, nombre) -> assertThat(nombre).as(type).isEqualTo(1));
    }

    private String inscrire() {
        AuthResponse r = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(uniqueEmail("badges-v122"), "MotDePasse123!", "Badges"))
            .exchange().expectStatus().isCreated()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return r.accessToken();
    }
}
