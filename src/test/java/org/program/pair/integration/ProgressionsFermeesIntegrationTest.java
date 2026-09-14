package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les progressions d'une personne ne se lisent plus que par elle, et plus aucune
 * série n'est servie — demande mobile badges TER du 14/09, étape intermédiaire.
 */
class ProgressionsFermeesIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;

    @Test
    void lesProgressionsDAutrui_privéesComprises_neSeLisentPlus() {
        Compte auteur = compte("prog-auteur");
        Compte curieux = compte("prog-curieux");
        UUID id = progressionPrivee(auteur);

        webTestClient.get().uri("/api/progressions/user/{id}", auteur.id())
            .headers(h -> h.setBearerAuth(curieux.token()))
            .exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/api/progressions/{id}", id)
            .headers(h -> h.setBearerAuth(curieux.token()))
            .exchange().expectStatus().isNotFound();

        webTestClient.get().uri("/api/progressions/user/{id}", auteur.id())
            .headers(h -> h.setBearerAuth(auteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.content[0].id").isEqualTo(id.toString());
    }

    @Test
    void aucuneSerieNEstServie() {
        Compte moi = compte("prog-serie");

        webTestClient.get().uri("/api/progressions/my/streak")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/api/progressions/my/stats")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.streak").doesNotExist();
    }

    private record Compte(UUID id, String token) {}

    private UUID progressionPrivee(Compte auteur) {
        Activity activity = activityRepository.findAll().get(0);
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(userRepository.findById(auteur.id()).orElseThrow()).activity(activity).build());
        Program program = programRepository.save(Program.builder()
            .userActivity(ua).title("Progressions " + UUID.randomUUID().toString().substring(0, 6))
            .status(ProgramStatus.ACTIVE).isPublic(true).build());
        Map<?, ?> corps = webTestClient.post().uri("/api/progressions")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("programId", program.getId().toString(), "title", "Séance privée", "isPublic", false))
            .exchange().expectStatus().is2xxSuccessful()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(corps).isNotNull();
        return UUID.fromString(String.valueOf(corps.get("id")));
    }

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
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
        return new Compte(userRepository.findByEmail(email).orElseThrow().getId(), auth.accessToken());
    }
}
