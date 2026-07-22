package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.MediaType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramMedia;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.search.FullTextSearchService;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UpdateLocationRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramMediaRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que FullTextSearchService — utilisé par searchPrograms/searchByActivity/
 * searchByTaxonomyLabels via le fragment SQL partagé PROGRAM_SELECT — expose
 * thumbnailUrl = program.imageUrl en priorité, avec repli sur le premier média de
 * la galerie uniquement si imageUrl est absent.
 */
class ProgramSearchThumbnailIntegrationTest extends AbstractIntegrationTest {

    @Autowired FullTextSearchService fullTextSearchService;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ProgramMediaRepository programMediaRepository;
    @Autowired UserRepository userRepository;

    @Test
    void searchByActivity_devraitRenvoyerImageUrl_quandPasDeGalerie() {
        Program program = createActiveProgram(
            "thumb-imageonly@pair.app", "Yoga couverture seule", "https://example.com/cover.png", false);

        SearchResultDto result = searchAndFind(program.getId());

        assertThat(result.thumbnailUrl()).isEqualTo("https://example.com/cover.png");
    }

    @Test
    void searchByActivity_devraitPrivilegierImageUrl_memeAvecGalerie() {
        Program program = createActiveProgram(
            "thumb-both@pair.app", "Yoga couverture et galerie", "https://example.com/cover.png", true);

        SearchResultDto result = searchAndFind(program.getId());

        assertThat(result.thumbnailUrl()).isEqualTo("https://example.com/cover.png");
    }

    @Test
    void searchByActivity_devraitReplierSurGalerie_sansImageUrl() {
        Program program = createActiveProgram(
            "thumb-gallery@pair.app", "Yoga galerie uniquement", null, true);

        SearchResultDto result = searchAndFind(program.getId());

        assertThat(result.thumbnailUrl()).isEqualTo("https://example.com/gallery-0.png");
    }

    private SearchResultDto searchAndFind(java.util.UUID programId) {
        SearchRequest request = new SearchRequest("yoga", 48.8566, 2.3522, null);
        List<SearchResultDto> results = fullTextSearchService.searchByActivity("yoga", request, 20);
        return results.stream()
            .filter(r -> r.id().equals(programId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Programme " + programId + " absent des résultats de recherche"));
    }

    private Program createActiveProgram(String email, String title, String imageUrl, boolean withGalleryMedia) {
        String token = registerAndLogin(email);
        updateLocation(token, 48.8566, 2.3522);

        User owner = userRepository.findByEmail(email).orElseThrow();
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();

        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(owner).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .imageUrl(imageUrl)
            .build());

        if (withGalleryMedia) {
            programMediaRepository.save(ProgramMedia.builder()
                .program(program)
                .url("https://example.com/gallery-0.png")
                .mediaType(MediaType.IMAGE)
                .sortOrder(0)
                .build());
        }

        return program;
    }

    private String registerAndLogin(String email) {
        RegisterRequest registerReq = new RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        LoginRequest loginReq = new LoginRequest(email, "Password123!");
        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
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
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }
}
