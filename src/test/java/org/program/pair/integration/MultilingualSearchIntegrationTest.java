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
import org.program.pair.domain.search.EmbeddingService;
import org.program.pair.domain.search.LlmIntentExtractor;
import org.program.pair.domain.search.dto.SearchIntent;
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
 * Vérifie le matching cross-lingue EN/DE/FR de la recherche : une requête dans
 * une langue doit remonter les programmes liés à une activité décrite dans une
 * autre langue, via la taxonomie déterministe ({@link org.program.pair.domain.search.ActivityTaxonomy}).
 *
 * LlmIntentExtractor et EmbeddingService sont mockés pour isoler et garantir la
 * couche taxonomique, indépendamment de toute API externe.
 */
class MultilingualSearchIntegrationTest extends AbstractIntegrationTest {

    // Seedée par V27__reset_and_seed_germany.sql : name="Laufen", description
    // "Laufsport für alle Niveaus — von der ersten Runde im Park bis zum Marathon."
    // (contenu entièrement en allemand, pas de traduction en base).
    private static final UUID RUNNING_ACTIVITY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000001");

    @MockitoBean
    LlmIntentExtractor intentExtractor;

    @MockitoBean
    EmbeddingService embeddingService;

    @Test
    void recherche_laufen_jogging_courseAPied_doiventToutesRemonterLeMemeProgramme() {
        // Le LLM est mocké : seule la couche taxonomie déterministe est testée ici.
        when(intentExtractor.extractIntent(any())).thenAnswer(invocation ->
            new SearchIntent(null, null, null, null, 5000, null, false, null, null));
        when(embeddingService.isConfigured()).thenReturn(false);

        String organizerToken = registerAndLogin("organizer-multi@pair.app");
        updateLocation(organizerToken, 48.8566, 2.3522);

        UUID userActivityId = addRunningActivity(organizerToken);
        UUID programId = createActiveProgram(organizerToken, userActivityId, "Sortie du dimanche");

        String searcherToken = registerAndLogin("searcher-multi@pair.app");
        updateLocation(searcherToken, 48.8566, 2.3522);

        // L'activité est stockée uniquement en allemand ("Laufen") : une requête
        // en anglais ou en français doit tout de même la retrouver.
        assertQueryFindsProgram(searcherToken, "Laufen", programId);            // DE (natif)
        assertQueryFindsProgram(searcherToken, "jogging", programId);           // EN -> DE
        assertQueryFindsProgram(searcherToken, "course à pied", programId);     // FR -> DE
    }

    private void assertQueryFindsProgram(String token, String query, UUID expectedProgramId) {
        SearchRequest searchReq = new SearchRequest(query, 48.8566, 2.3522, 20000);

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

        assertThat(response).isNotNull();
        assertThat(response.type()).isEqualTo("results");
        assertThat(response.results())
            .as("query '%s' devrait remonter le programme %s", query, expectedProgramId)
            .anyMatch(r -> r.id().equals(expectedProgramId));
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
            userActivityId, title, "Programme ouvert à tous.", true,
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
            null, null, ProgramStatus.ACTIVE, true,
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
