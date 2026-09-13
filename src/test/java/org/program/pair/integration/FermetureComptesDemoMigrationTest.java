package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-02 étape 5 — ce que V116 fait quand son placeholder vaut {@code true},
 * c'est-à-dire en production.
 *
 * <p>La base de test a joué V116 avec {@code false} : rien n'y a été touché. Ce
 * test rejoue le script avec {@code true}, dans une transaction annulée à la
 * fin — la base est partagée, et d'autres classes peuvent y tenir des comptes
 * {@code demo…@pair.app}.
 */
@Transactional
class FermetureComptesDemoMigrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void enProduction_lesComptesDemoFerment_etLesAutresRestentIntacts() throws IOException {
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        UUID demo = inserer("demo-" + suffixe + "@pair.app", "$2a$10$empreinteDemo");
        UUID vrai = inserer("vrai-" + suffixe + "@pair.app", "$2a$10$empreinteVraie");
        UUID voisin = inserer("demo-" + suffixe + "@pair.app.example", "$2a$10$empreinteVoisine");
        UUID session = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO refresh_sessions (id, user_id, created_at, last_used_at) "
            + "VALUES (?, ?, now(), now())", session, demo);

        jdbcTemplate.execute(script().replace("${fermer_comptes_demo}", "true"));

        Map<String, Object> ferme = compte(demo);
        assertThat(ferme.get("is_active")).isEqualTo(false);
        assertThat(ferme.get("password_hash")).isEqualTo("!compte-demo-ferme");
        assertThat(ferme.get("token_version")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT revoked_reason FROM refresh_sessions WHERE id = ?", String.class, session))
            .isEqualTo("COMPTE_DEMO_FERME");

        for (UUID intact : new UUID[] {vrai, voisin}) {
            Map<String, Object> ligne = compte(intact);
            assertThat(ligne.get("is_active")).as("compte hors motif").isEqualTo(true);
            assertThat((String) ligne.get("password_hash")).startsWith("$2a$");
            assertThat(ligne.get("token_version")).isEqualTo(0);
        }
    }

    @Test
    void horsProduction_leScriptNeToucheRien() throws IOException {
        UUID demo = inserer("demo-" + UUID.randomUUID().toString().substring(0, 8) + "@pair.app",
            "$2a$10$empreinteDemo");

        jdbcTemplate.execute(script().replace("${fermer_comptes_demo}", "false"));

        assertThat(compte(demo).get("is_active")).isEqualTo(true);
    }

    private UUID inserer(String email, String empreinte) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, display_name, is_active) "
            + "VALUES (?, ?, ?, 'Test migration', true)", id, email, empreinte);
        return id;
    }

    private Map<String, Object> compte(UUID id) {
        return jdbcTemplate.queryForMap(
            "SELECT is_active, password_hash, token_version FROM users WHERE id = ?", id);
    }

    private static String script() throws IOException {
        return new ClassPathResource("db/migration/V116__fermeture_comptes_demo.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    }
}
