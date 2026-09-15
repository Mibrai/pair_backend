package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/search/popular} ne publie que des termes du catalogue, cherchés
 * par au moins cinq personnes, sans compteur — demande mobile recherche du 15/09.
 */
class RecherchesPopulairesIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void seulsLesTermesDuCatalogue_chercheesParCinqPersonnes_sontPublies() {
        // Des lettres seulement : un chiffre ou un tiret changerait la normalisation.
        String suffixe = new java.util.Random().ints(8, 'a', 'z' + 1)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String populaire = activite("Canyoning" + suffixe);
        String tropRare = activite("Spéléologie" + suffixe);
        String unNom = "Lena Mueller " + suffixe;

        // Saisie libre : minuscules, sans accent, espaces autour — ramenée au catalogue.
        chercher(populaire.toLowerCase() + "  ", 5);
        chercher("speleologie" + suffixe, 4);
        chercher(unNom, 6);

        String lecteur = compte("populaires-lecteur");
        List<Map<String, Object>> reponse = webTestClient.get()
            .uri(b -> b.path("/api/search/popular").queryParam("limit", 50).build())
            .headers(h -> h.setBearerAuth(lecteur))
            .exchange().expectStatus().isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {})
            .returnResult().getResponseBody();

        assertThat(reponse).isNotNull();
        List<Object> termes = reponse.stream().map(e -> e.get("query")).toList();
        assertThat(termes)
            .as("le libellé du catalogue, pas la saisie")
            .contains(populaire)
            .doesNotContain(tropRare, unNom, unNom.toLowerCase());
        assertThat(reponse).allSatisfy(e -> assertThat(e).containsOnlyKeys("query"));
    }

    private String activite(String nom) {
        activityRepository.save(Activity.builder()
            .name(nom)
            .slug("populaire-" + UUID.randomUUID())
            .category(categoryRepository.findAll().get(0))
            .createdAt(Instant.now())
            .build());
        return nom;
    }

    /** {@code personnes} comptes distincts saisissent la même requête. */
    private void chercher(String saisie, int personnes) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < personnes; i++) {
            String email = uniqueEmail("populaires");
            compteAvec(email);
            ids.add(jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email));
        }
        for (UUID id : ids) {
            jdbcTemplate.update(
                "INSERT INTO search_logs (id, user_id, raw_query, searched_at) VALUES (gen_random_uuid(), ?, ?, now())",
                id, saisie);
        }
    }

    private String compte(String prefixe) {
        return compteAvec(uniqueEmail(prefixe));
    }

    private String compteAvec(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Testeur"))
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
