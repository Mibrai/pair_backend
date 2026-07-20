package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.dto.UserActivityDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la régression décrite dans docs/BACKEND_PROFILE_FIX.md :
 * GET /api/users/{id} renvoyait 500 pour tout utilisateur ayant un badge dont
 * la catégorie en base ('CREATION', voir V12/V27) n'existait pas dans
 * BadgeCategory — et GET /api/users/{id}/activities n'existait pas du tout.
 */
class UserPublicProfileIntegrationTest extends AbstractIntegrationTest {

    // Seedée par V27, id fixe : Lena Müller, a le badge ACTIVE_COACH
    // (catégorie 'CREATION' en base) — c'est ce qui déclenchait le 500.
    private static final UUID SEEDED_USER_WITH_CREATION_BADGE =
        UUID.fromString("00000000-0000-0000-0000-000000000002");

    private String registerAndGetToken(String email) {
        AuthResponse resp = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "MotDePasse123!", "Test User"))
            .exchange()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();
        return resp.accessToken();
    }

    @Test
    void getPublicProfile_devraitRenvoyer200_pourUnUtilisateurAvecBadgeCreation() {
        String token = registerAndGetToken("viewer1@pair.app");

        UserPublicDto dto = webTestClient.get()
            .uri("/api/users/{id}", SEEDED_USER_WITH_CREATION_BADGE)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPublicDto.class)
            .returnResult().getResponseBody();

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(SEEDED_USER_WITH_CREATION_BADGE);
        assertThat(dto.displayName()).isEqualTo("Lena Müller");
        assertThat(dto.badgeCodes()).contains("ACTIVE_COACH");
    }

    @Test
    void getPublicProfile_devraitRenvoyer404Propre_pourUnIdInexistant() {
        String token = registerAndGetToken("viewer2@pair.app");
        UUID unknownId = UUID.randomUUID();

        ErrorResponse error = webTestClient.get()
            .uri("/api/users/{id}", unknownId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void getUserActivities_devraitRenvoyer200_avecLesActivitesPubliquesDeCetUtilisateur() {
        String token = registerAndGetToken("viewer3@pair.app");

        List<UserActivityDto> activities = webTestClient.get()
            .uri("/api/users/{id}/activities", SEEDED_USER_WITH_CREATION_BADGE)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(UserActivityDto.class)
            .returnResult().getResponseBody();

        assertThat(activities).isNotNull();
        assertThat(activities).isNotEmpty();
        assertThat(activities).allMatch(UserActivityDto::visibleOnMap);
    }

    @Test
    void getUserActivities_devraitRenvoyer404_pourUnIdInexistant() {
        String token = registerAndGetToken("viewer4@pair.app");
        UUID unknownId = UUID.randomUUID();

        webTestClient.get()
            .uri("/api/users/{id}/activities", unknownId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isNotFound();
    }
}
