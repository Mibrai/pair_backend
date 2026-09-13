package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.program.dto.ScheduleDto;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * P-MU-16 — « Frais à prévoir » : un booléen et une précision, jamais un prix.
 *
 * <p>Le scénario de vérification du client, au mot près : un programme « Tennis »
 * avec « Location du terrain », relu par la fiche, par {@code /search} et par
 * {@code /slots/feed} ; un programme sans frais qui ne rend ni l'un ni l'autre.
 *
 * <p>Décor géographique à soi (Angers) et titres uniques : la base est partagée.
 */
class ProgramCostToShareIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 47.4784;
    private static final double LNG = -0.5632;

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;

    @Test
    void desFraisAnnonces_doiventSeRelire_surLaFiche_leFil_etLaRecherche() {
        doReturn(new float[384]).when(embeddingService).generateEmbedding(any());
        String hostEmail = uniqueEmail("cost-host");
        String host = registerAndLogin(hostEmail);
        String viewer = registerAndLogin(uniqueEmail("cost-viewer"));

        ProgramDto created = createProgram(host, hostEmail, "Tennis " + UUID.randomUUID(),
            true, "Location du terrain");
        assertThat(created.costToShare()).isTrue();
        assertThat(created.costNote()).isEqualTo("Location du terrain");

        UUID slotId = publish(host, created.id());

        ProgramDto sheet = program(viewer, created.id());
        assertThat(sheet.costToShare()).isTrue();
        assertThat(sheet.costNote()).isEqualTo("Location du terrain");

        SlotFeedItemDto item = feedItem(viewer, slotId);
        assertThat(item.costToShare()).isTrue();
        assertThat(item.costNote()).isEqualTo("Location du terrain");

        SearchResponse response = search(viewer, "tennis");
        SearchResultDto programResult = result(response, "program", created.id());
        assertThat(programResult.costToShare()).isTrue();
        assertThat(programResult.costNote()).isEqualTo("Location du terrain");
        SearchResultDto slotResult = result(response, "slot", slotId);
        assertThat(slotResult.costToShare()).isTrue();
        assertThat(slotResult.costNote()).isEqualTo("Location du terrain");
    }

    @Test
    void unProgrammeSansFrais_neDoitRendreNiLUnNiLAutre() {
        doReturn(new float[384]).when(embeddingService).generateEmbedding(any());
        String hostEmail = uniqueEmail("cost-host");
        String host = registerAndLogin(hostEmail);
        String viewer = registerAndLogin(uniqueEmail("cost-viewer"));

        ProgramDto created = createProgram(host, hostEmail, "Tennis libre " + UUID.randomUUID(),
            null, null);
        UUID slotId = publish(host, created.id());

        ProgramDto sheet = program(viewer, created.id());
        assertThat(sheet.costToShare()).isFalse();
        assertThat(sheet.costNote()).isNull();

        SlotFeedItemDto item = feedItem(viewer, slotId);
        assertThat(item.costToShare()).isFalse();
        assertThat(item.costNote()).isNull();

        SearchResponse response = search(viewer, "tennis");
        for (SearchResultDto r : List.of(result(response, "program", created.id()),
                                         result(response, "slot", slotId))) {
            assertThat(r.costToShare()).isFalse();
            assertThat(r.costNote()).isNull();
        }
    }

    @Test
    void unePrecisionSansLaCase_doitEtreIgnoree() {
        String hostEmail = uniqueEmail("cost-host");
        String host = registerAndLogin(hostEmail);

        ProgramDto created = createProgram(host, hostEmail, "Tennis précis " + UUID.randomUUID(),
            false, "Location du terrain");

        assertThat(created.costToShare()).isFalse();
        assertThat(created.costNote()).isNull();
    }

    @Test
    void modifier_doitSuivreLaCase_etNeRienEffacerQuandLesClesManquent() {
        String hostEmail = uniqueEmail("cost-host");
        String host = registerAndLogin(hostEmail);
        ProgramDto created = createProgram(host, hostEmail, "Tennis modifié " + UUID.randomUUID(),
            true, "Location du terrain");

        // Un client qui ignore encore ces champs ne doit pas les effacer.
        ProgramDto untouched = patch(host, created.id(), Map.of("title", "Tennis du dimanche"));
        assertThat(untouched.costToShare()).isTrue();
        assertThat(untouched.costNote()).isEqualTo("Location du terrain");

        ProgramDto renamed = put(host, created.id(), Map.of("costNote", "Balles fournies, terrain à partager"));
        assertThat(renamed.costNote()).isEqualTo("Balles fournies, terrain à partager");

        ProgramDto cleared = patch(host, created.id(), Map.of("costNote", ""));
        assertThat(cleared.costToShare()).isTrue();
        assertThat(cleared.costNote()).isNull();

        patch(host, created.id(), Map.of("costNote", "Location du terrain"));
        ProgramDto unchecked = patch(host, created.id(), Map.of("costToShare", false));
        assertThat(unchecked.costToShare()).isFalse();
        assertThat(unchecked.costNote())
            .as("décocher la case efface la précision qu'elle portait").isNull();

        // Une précision envoyée alors que la case reste décochée n'est pas retenue.
        assertThat(patch(host, created.id(), Map.of("costNote", "Entrée 5 €")).costNote()).isNull();

        // Recocher ne ressuscite rien.
        ProgramDto rechecked = patch(host, created.id(), Map.of("costToShare", true));
        assertThat(rechecked.costToShare()).isTrue();
        assertThat(rechecked.costNote()).isNull();
    }

    @Test
    void unePrecisionTropLongue_doitEtreRefusee() {
        String hostEmail = uniqueEmail("cost-host");
        String host = registerAndLogin(hostEmail);
        ProgramDto created = createProgram(host, hostEmail, "Tennis long " + UUID.randomUUID(),
            true, "Location du terrain");

        webTestClient.patch().uri("/api/programs/{id}", created.id())
            .headers(h -> h.setBearerAuth(host))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("costNote", "x".repeat(81)))
            .exchange().expectStatus().isBadRequest();

        assertThat(program(host, created.id()).costNote()).isEqualTo("Location du terrain");
    }

    @Test
    void dupliquer_doitRecopierLesFrais() {
        String hostEmail = uniqueEmail("cost-host");
        String host = registerAndLogin(hostEmail);
        ProgramDto created = createProgram(host, hostEmail, "Tennis copié " + UUID.randomUUID(),
            true, "Location du terrain");

        ProgramDto copy = webTestClient.post().uri("/api/programs/{id}/duplicate", created.id())
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isCreated()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();

        assertThat(copy.costToShare()).isTrue();
        assertThat(copy.costNote()).isEqualTo("Location du terrain");
    }

    // — helpers —

    private ProgramDto createProgram(String token, String email, String title,
                                     Boolean costToShare, String costNote) {
        var owner = userRepository.findByEmail(email).orElseThrow();
        Activity tennis = activityRepository.findBySlug("tennis").orElseThrow();
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(owner).activity(tennis).build());

        Map<String, Object> body = new HashMap<>();
        body.put("userActivityId", ua.getId());
        body.put("title", title);
        body.put("isPublic", true);
        if (costToShare != null) body.put("costToShare", costToShare);
        if (costNote != null) body.put("costNote", costNote);

        ProgramDto dto = webTestClient.post().uri("/api/programs")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isCreated()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();
        assertThat(dto).isNotNull();
        return dto;
    }

    /** Active le programme et lui pose un créneau : sans eux, ni fil ni recherche. */
    private UUID publish(String token, UUID programId) {
        put(token, programId, Map.of("status", "ACTIVE"));

        Map<String, Object> slot = new HashMap<>();
        slot.put("placeName", "Tennis club du Lac de Maine");
        slot.put("placeType", "PUBLIC");
        slot.put("lat", LAT);
        slot.put("lng", LNG);
        slot.put("addressPublic", "Avenue du Lac de Maine, Angers");
        Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        slot.put("startsAt", startsAt.toString());
        slot.put("endsAt", startsAt.plus(1, ChronoUnit.HOURS).toString());
        slot.put("maxParticipants", 4);

        ScheduleDto created = webTestClient.post().uri("/api/programs/{id}/schedules", programId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(slot)
            .exchange().expectStatus().isCreated()
            .expectBody(ScheduleDto.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        return created.id();
    }

    private ProgramDto patch(String token, UUID programId, Map<String, Object> body) {
        return webTestClient.patch().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();
    }

    private ProgramDto put(String token, UUID programId, Map<String, Object> body) {
        return webTestClient.put().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();
    }

    private ProgramDto program(String token, UUID programId) {
        return webTestClient.get().uri("/api/programs/{id}", programId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();
    }

    private SlotFeedItemDto feedItem(String token, UUID scheduleId) {
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000).build())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return feed.stream().filter(i -> scheduleId.equals(i.scheduleId())).findFirst()
            .orElseThrow(() -> new AssertionError("Créneau absent du fil : " + scheduleId));
    }

    private SearchResponse search(String token, String query) {
        return webTestClient.post().uri("/api/search")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest(query, LAT, LNG, 20000))
            .exchange().expectStatus().isOk()
            .expectBody(SearchResponse.class).returnResult().getResponseBody();
    }

    private SearchResultDto result(SearchResponse response, String type, UUID id) {
        return response.results().stream()
            .filter(r -> type.equals(r.resultType()) && id.equals(r.id()))
            .findFirst().orElseThrow(() -> new AssertionError(
                "Résultat " + type + " " + id + " absent de : " + response.results()));
    }

    private String registerAndLogin(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Organisateur"))
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
