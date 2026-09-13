package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.ScheduleDto;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
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
 * P-MU-07 — le niveau affiché sur un créneau est celui du créneau.
 *
 * <p>Chaque hôte de cette classe s'est déclaré <b>ADVANCED</b> sur son profil.
 * C'est tout le décor : ce niveau-là s'affichait sur ses créneaux comme une
 * exigence qu'il n'avait jamais formulée, et aucune assertion ne doit plus
 * pouvoir le voir ressortir.
 *
 * <p>Décor géographique à soi (La Rochelle) et titres uniques : la base est
 * partagée avec le reste de la suite.
 */
class SlotDeclaredLevelIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 46.1603;
    private static final double LNG = -1.1511;

    @MockitoBean LocalEmbeddingService embeddingService;

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;

    @Test
    void unCreneauDeclare_doitRendreSonNiveau_partout() {
        doReturn(new float[384]).when(embeddingService).generateEmbedding(any());
        String hostEmail = uniqueEmail("level-host");
        String host = registerAndLogin(hostEmail);
        String viewer = registerAndLogin(uniqueEmail("level-viewer"));
        String title = "Yoga débutants " + UUID.randomUUID();
        UUID programId = programOfAdvancedHost(hostEmail, title);

        ScheduleDto created = addSchedule(host, programId, "BEGINNER");
        assertThat(created.level()).isEqualTo("BEGINNER");

        assertThat(slot(viewer, created.id()).level()).isEqualTo("BEGINNER");
        assertThat(feedItem(viewer, created.id()).level()).isEqualTo("BEGINNER");

        SearchResponse response = search(viewer, "du yoga");
        SearchResultDto slotResult = response.results().stream()
            .filter(r -> "slot".equals(r.resultType()) && created.id().equals(r.id()))
            .findFirst().orElseThrow(() -> new AssertionError(
                "Créneau absent de la recherche : " + response.results()));
        assertThat(slotResult.level()).isEqualTo("BEGINNER");

        // Le résultat « programme » ne porte pas de niveau : seul le créneau en
        // déclare un, et celui de l'hôte n'en est pas un.
        response.results().stream()
            .filter(r -> "program".equals(r.resultType()) && programId.equals(r.id()))
            .forEach(r -> assertThat(r.level()).isNull());
    }

    @Test
    void chercherUnNiveau_neDoitPasEcarterUnProgramme_quiNenDeclareAucun() {
        // Le filtre de niveau lisait le profil de l'hôte : « yoga débutant »
        // écartait ce programme parce que son organisateur s'était dit avancé.
        // Depuis que ce niveau n'est plus rendu, une égalité stricte aurait
        // écarté TOUS les programmes.
        doReturn(new float[384]).when(embeddingService).generateEmbedding(any());
        String hostEmail = uniqueEmail("level-host");
        String host = registerAndLogin(hostEmail);
        String viewer = registerAndLogin(uniqueEmail("level-viewer"));
        UUID programId = programOfAdvancedHost(hostEmail, "Yoga du port " + UUID.randomUUID());
        // Un créneau localisé : c'est lui qui place le programme dans le rayon.
        addSchedule(host, programId, null);

        SearchResponse response = search(viewer, "yoga debutant");

        assertThat(response.parsedIntent().level()).isEqualTo("BEGINNER");
        assertThat(response.results())
            .anyMatch(r -> "program".equals(r.resultType()) && programId.equals(r.id()));
    }

    @Test
    void unCreneauSansNiveau_neDoitJamaisRendreCeluiDeLHote() {
        String hostEmail = uniqueEmail("level-host");
        String host = registerAndLogin(hostEmail);
        String viewer = registerAndLogin(uniqueEmail("level-viewer"));
        UUID programId = programOfAdvancedHost(hostEmail, "Yoga sans niveau " + UUID.randomUUID());

        ScheduleDto created = addSchedule(host, programId, null);

        assertThat(created.level()).isNull();
        assertThat(slot(viewer, created.id()).level())
            .as("le niveau ADVANCED du profil de l'hôte ne doit pas ressortir").isNull();
        assertThat(feedItem(viewer, created.id()).level()).isNull();
    }

    @Test
    void modifierSansLaCle_doitGarderLeNiveau_etLaChaineVideLeRetire() {
        // La question du §3 : une modification faite par un client qui ignore
        // encore le champ ne doit pas l'effacer.
        String hostEmail = uniqueEmail("level-host");
        String host = registerAndLogin(hostEmail);
        UUID programId = programOfAdvancedHost(hostEmail, "Yoga modifié " + UUID.randomUUID());
        ScheduleDto created = addSchedule(host, programId, "BEGINNER");

        ScheduleDto untouched = updateSchedule(host, programId, created.id(),
            Map.of("welcomeNote", "Tapis fournis"));
        assertThat(untouched.welcomeNote()).isEqualTo("Tapis fournis");
        assertThat(untouched.level()).isEqualTo("BEGINNER");

        Map<String, Object> explicitNull = new HashMap<>();
        explicitNull.put("level", null);
        assertThat(updateSchedule(host, programId, created.id(), explicitNull).level())
            .as("null vaut « ne touche pas », comme partout dans cette requête")
            .isEqualTo("BEGINNER");

        // Une valeur que le formulaire ne propose pas reste lisible.
        assertThat(updateSchedule(host, programId, created.id(), Map.of("level", "EXPERT")).level())
            .isEqualTo("EXPERT");

        assertThat(updateSchedule(host, programId, created.id(), Map.of("level", "")).level())
            .isNull();
        assertThat(slot(host, created.id()).level()).isNull();
    }

    @Test
    void unNiveauInconnu_doitEtreRefuse() {
        String hostEmail = uniqueEmail("level-host");
        String host = registerAndLogin(hostEmail);
        UUID programId = programOfAdvancedHost(hostEmail, "Yoga refusé " + UUID.randomUUID());

        webTestClient.post().uri("/api/programs/{id}/schedules", programId)
            .headers(h -> h.setBearerAuth(host))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(scheduleBody("PRO"))
            .exchange().expectStatus().isBadRequest();

        ScheduleDto created = addSchedule(host, programId, "ANY");
        webTestClient.put().uri("/api/programs/{p}/schedules/{s}", programId, created.id())
            .headers(h -> h.setBearerAuth(host))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("level", "PRO"))
            .exchange().expectStatus().isBadRequest();
        assertThat(slot(host, created.id()).level()).isEqualTo("ANY");
    }

    @Test
    void leCreneauRapide_doitPorterLeNiveauAttendu_surLeCreneau() {
        String host = registerAndLogin(uniqueEmail("level-quick"));
        UUID activityId = activityRepository.findBySlug("yoga").orElseThrow().getId();

        SlotFeedItemDto declared = quickSlot(host, activityId, ActivityLevel.BEGINNER);
        assertThat(declared.level()).isEqualTo("BEGINNER");

        // Sans niveau : le profil reçoit ANY par défaut, le créneau reste muet.
        SlotFeedItemDto silent = quickSlot(host, activityId, null);
        assertThat(silent.level()).isNull();
    }

    // — helpers —

    private UUID programOfAdvancedHost(String hostEmail, String title) {
        var owner = userRepository.findByEmail(hostEmail).orElseThrow();
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(owner).activity(yoga).level(ActivityLevel.ADVANCED).build());
        return programRepository.save(Program.builder()
            .userActivity(ua)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build()).getId();
    }

    private Map<String, Object> scheduleBody(String level) {
        Map<String, Object> body = new HashMap<>();
        body.put("placeName", "Parc Charruyer");
        body.put("placeType", "PUBLIC");
        body.put("lat", LAT);
        body.put("lng", LNG);
        body.put("addressPublic", "Allée du Mail, La Rochelle");
        Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        body.put("startsAt", startsAt.toString());
        body.put("endsAt", startsAt.plus(1, ChronoUnit.HOURS).toString());
        body.put("maxParticipants", 8);
        if (level != null) {
            body.put("level", level);
        }
        return body;
    }

    private ScheduleDto addSchedule(String token, UUID programId, String level) {
        ScheduleDto dto = webTestClient.post().uri("/api/programs/{id}/schedules", programId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(scheduleBody(level))
            .exchange().expectStatus().isCreated()
            .expectBody(ScheduleDto.class).returnResult().getResponseBody();
        assertThat(dto).isNotNull();
        return dto;
    }

    private ScheduleDto updateSchedule(String token, UUID programId, UUID scheduleId,
                                       Map<String, Object> body) {
        return webTestClient.put().uri("/api/programs/{p}/schedules/{s}", programId, scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(ScheduleDto.class).returnResult().getResponseBody();
    }

    private SlotFeedItemDto slot(String token, UUID scheduleId) {
        return webTestClient.get().uri("/api/slots/{id}", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
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

    private SlotFeedItemDto quickSlot(String token, UUID activityId, ActivityLevel level) {
        return webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Vieux-Port", PlaceType.PUBLIC, LAT, LNG,
                "Quai Duperré, La Rochelle", null, "La Rochelle", 5, null, level, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
    }

    private SearchResponse search(String token, String query) {
        return webTestClient.post().uri("/api/search")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest(query, LAT, LNG, 20000))
            .exchange().expectStatus().isOk()
            .expectBody(SearchResponse.class).returnResult().getResponseBody();
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
