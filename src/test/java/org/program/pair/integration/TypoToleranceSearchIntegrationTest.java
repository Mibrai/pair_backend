package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.activity.dto.UpsertUserActivityRequest;
import org.program.pair.domain.activity.dto.UserActivityDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.dto.CreateProgramRequest;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.program.dto.UpdateProgramRequest;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.user.dto.UpdateLocationRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Lot D7 — tolérance aux fautes de frappe.
 *
 * <p>Le repli trigramme est la <b>quatrième</b> couche, et son ordre est tout le
 * lot : il ne s'exécute que si la taxonomie, le sémantique et le plein texte
 * n'ont rien rendu. Ces tests vérifient donc les deux moitiés du contrat — qu'une
 * faute de frappe soit rattrapée, et qu'une requête qui fonctionnait ne soit pas
 * dégradée.
 *
 * <p>Le vecteur d'embedding est nul, comme dans le test multilingue : cela force
 * le repli plein texte et rend la couche testée indépendante du modèle local.
 */
class TypoToleranceSearchIntegrationTest extends AbstractIntegrationTest {

    // Seedée par V27 : name="Laufen" (course à pied), description en allemand.
    private static final UUID RUNNING_ACTIVITY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000001");

    private static final double LAT = 48.8566;
    private static final double LNG = 2.3522;

    @MockitoBean
    LocalEmbeddingService embeddingService;

    @Test
    void uneFauteDeFrappe_doitQuandMemeTrouverLActivite() {
        // « Laufne » ne matche ni la taxonomie ni le plein texte : sans le repli,
        // la réponse est vide, et son auteur en conclut que l'application n'a rien
        // près de chez lui plutôt qu'il s'est trompé d'une lettre.
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
        Fixture f = fixture("typo-a");

        assertFinds(f.searcher, "Laufne", f.programId);
    }

    @Test
    void uneFauteDeFrappe_doitAussiPorterSurLeTitreDuProgramme() {
        // Quelqu'un qui tape « Sortie du dimnche » vise un titre, pas une
        // activité : la similarité retient la meilleure des deux.
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
        Fixture f = fixture("typo-b");

        assertFinds(f.searcher, "Sortie du dimnche", f.programId);
    }

    @Test
    void uneRequeteExacte_doitContinuerDeFonctionner() {
        // La garantie de non-régression du lot : le repli ne s'interpose pas.
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
        Fixture f = fixture("typo-c");

        assertFinds(f.searcher, "Laufen", f.programId);
    }

    @Test
    void uneRequeteSansRapport_doitRendreVide_plutotQueNimporteQuoi() {
        // Le seuil existe pour cela. Une liste de résultats sans rapport se lit
        // comme une panne du produit, là où une liste vide se lit comme une
        // absence — et c'est bien une absence.
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
        Fixture f = fixture("typo-d");

        SearchResponse response = search(f.searcher, "zzzqqqwwwxxx");

        assertThat(response).isNotNull();
        assertThat(response.results())
            .as("aucun programme ne ressemble à cette requête")
            .noneMatch(r -> r.id().equals(f.programId));
    }

    // — helpers —

    private record Fixture(String searcher, UUID programId) {}

    private Fixture fixture(String prefix) {
        String organizer = registerAndLogin(prefix + "-org@pair.app");
        updateLocation(organizer, LAT, LNG);
        UUID userActivityId = addRunningActivity(organizer);
        UUID programId = createActiveProgram(organizer, userActivityId, "Sortie du dimanche");

        String searcher = registerAndLogin(prefix + "-search@pair.app");
        updateLocation(searcher, LAT, LNG);
        return new Fixture(searcher, programId);
    }

    private void assertFinds(String token, String query, UUID expectedProgramId) {
        SearchResponse response = search(token, query);

        assertThat(response).isNotNull();
        assertThat(response.results())
            .as("la requête « %s » devrait remonter le programme %s", query, expectedProgramId)
            .anyMatch(r -> r.id().equals(expectedProgramId));
    }

    private SearchResponse search(String token, String query) {
        return webTestClient.post()
            .uri("/api/search")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest(query, LAT, LNG, 20000))
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private UUID addRunningActivity(String token) {
        UpsertUserActivityRequest req = new UpsertUserActivityRequest(
            RUNNING_ACTIVITY_ID, true, null, ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP);

        UserActivityDto dto = webTestClient.post()
            .uri("/api/users/me/activities")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(UserActivityDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(dto).isNotNull();
        return dto.id();
    }

    private UUID createActiveProgram(String token, UUID userActivityId, String title) {
        CreateProgramRequest createReq = new CreateProgramRequest(
            userActivityId, title, "Programme ouvert à tous.", true, null,
            null, null, null, null, null, null, null, null, null, null);

        ProgramDto created = webTestClient.post()
            .uri("/api/programs")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createReq)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(ProgramDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(created).isNotNull();

        UpdateProgramRequest activateReq = new UpdateProgramRequest(
            null, null, ProgramStatus.ACTIVE, true, null,
            null, null, null, null, null, null, null, null, null, null);

        webTestClient.put()
            .uri("/api/programs/{id}", created.id())
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(activateReq)
            .exchange()
            .expectStatus().isOk();

        return created.id();
    }

    private String registerAndLogin(String email) {
        String displayName = email.substring(0, email.indexOf("@"));
        String password = "Password123!";

        RegisterRequest registerReq = new RegisterRequest(email, password, displayName);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

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
