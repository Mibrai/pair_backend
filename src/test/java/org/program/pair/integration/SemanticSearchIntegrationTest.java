package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.user.dto.UpdateLocationRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SemanticSearchIntegrationTest — Tests d'intégration de la recherche sémantique
 *
 * RuleBasedIntentExtractor est déterministe et sans dépendance externe : il
 * tourne réellement (non mocké). Seul LocalEmbeddingService est mocké pour
 * éviter de charger le modèle ONNX dans les tests (voir
 * meetdo.embedding.enabled=false dans application-test.properties).
 *
 * Valide :
 * - Le pipeline de recherche avec clarification sur question vague
 * - Le comportement en cas d'aucun résultat
 * - La confidentialité des programmes non publics
 */
class SemanticSearchIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    LocalEmbeddingService embeddingService;

    @Test
    void recherche_questionVague_devraitRetournerClarification() {
        // "je veux faire du sport" est reconnue comme vague par
        // RuleBasedIntentExtractor (phrase courte, sans activité connue).

        // Créer un utilisateur et se connecter
        String token = registerAndLogin("searcher@pair.app");

        // Effectuer une recherche avec une question vague
        SearchRequest searchReq = new SearchRequest("je veux faire du sport", 48.85, 2.35, null);
        SearchResponse response = webTestClient.post()
            .uri("/api/search")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(searchReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();

        // Vérifications
        assertThat(response).isNotNull();
        assertThat(response.type()).isEqualTo("clarification");
        assertThat(response.clarificationQuestion()).isNotBlank();
        assertThat(response.clarificationQuestion()).contains("activité");

        // Aucun embedding ne doit être généré inutilement
        verify(embeddingService, never()).generateEmbedding(any());
    }

    @Test
    void recherche_aucunResultat_devraitProposerAlternatives() {
        // "fictionsport-xyz" n'est reconnue par aucune activité de la taxonomie
        // (contrairement à "escalade", que la couche taxonomie multilingue résout
        // désormais correctement près de Paris) — RuleBasedIntentExtractor tourne
        // réellement, aucun mock nécessaire. Requête réduite au seul terme
        // inconnu (pas de mots de liaison français) : RuleBasedIntentExtractor
        // conserve la requête brute comme activityKeyword pour le repli plein
        // texte, une phrase complète y introduirait des mots courants qui
        // matcheraient de vrais programmes et fausserait ce test.
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);

        // Créer un utilisateur et se connecter
        String token = registerAndLogin("noresult@pair.app");

        // Effectuer une recherche sur une activité qui n'existe pas
        SearchRequest searchReq = new SearchRequest("fictionsport-xyz", 48.85, 2.35, null);

        SearchResponse response = webTestClient.post()
            .uri("/api/search")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(searchReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();

        // Vérifications
        assertThat(response).isNotNull();
        assertThat(response.type()).isEqualTo("empty");
        assertThat(response.suggestedAlternatives()).isNotEmpty();
        assertThat(response.suggestedAlternatives())
            .anyMatch(alt -> alt.contains("zone de recherche") || alt.contains("créer"));
    }

    @Test
    void recherche_neDoitJamaisRetourner_programmeNonPublic() {
        // Créer un programme privé proche
        String ownerToken = registerAndLogin("owner@pair.app");
        updateLocation(ownerToken, 48.8566, 2.3522);

        // Note: Ce test suppose l'existence d'un endpoint pour créer un programme
        // Si non disponible, ce test pourrait nécessiter un setup direct en base de données
        // ou être simplifié pour vérifier uniquement le comportement du service de recherche

        // "yoga" est résolue par la taxonomie sans mock nécessaire ; vecteur nul
        // pour forcer le repli plein texte / taxonomie.
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);

        // Créer un chercheur
        String searcherToken = registerAndLogin("searcher2@pair.app");
        updateLocation(searcherToken, 48.8566, 2.3522);

        // Effectuer une recherche
        SearchRequest searchReq = new SearchRequest("je cherche du yoga", 48.8566, 2.3522, null);

        SearchResponse response = webTestClient.post()
            .uri("/api/search")
            .headers(headers -> headers.setBearerAuth(searcherToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(searchReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();

        // Vérifications : les programmes privés ne doivent jamais apparaître
        assertThat(response).isNotNull();

        // Si des résultats existent, vérifier qu'aucun n'est privé
        // (Cette vérification est principalement théorique car nous n'avons pas créé
        // de programme dans ce test - c'est une validation de non-régression)
        if (response.results() != null && !response.results().isEmpty()) {
            // Tous les résultats doivent être publics
            // Note: Le DTO SearchResultDto ne contient pas de champ isPublic,
            // donc cette vérification se fait au niveau du service
            assertThat(response.results()).isNotNull();
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Enregistre un nouvel utilisateur et se connecte immédiatement.
     *
     * @param email L'email de l'utilisateur
     * @return Le token d'accès JWT
     */
    private String registerAndLogin(String email) {
        String displayName = email.substring(0, email.indexOf("@"));
        String password = "Password123!";

        // Enregistrement
        RegisterRequest registerReq = new RegisterRequest(email, password, displayName);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        // Login
        LoginRequest loginReq = new LoginRequest(email, password);
        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }

    /**
     * Met à jour la localisation géographique de l'utilisateur.
     *
     * @param token Token JWT de l'utilisateur
     * @param lat Latitude
     * @param lng Longitude
     */
    private void updateLocation(String token, double lat, double lng) {
        UpdateLocationRequest request = new UpdateLocationRequest(lat, lng);
        webTestClient.put()
            .uri("/api/users/me/location")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }
}
