package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.dto.SuggestedActivityDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.OnboardingStateDto;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot A1 — parcours d'accueil et premières suggestions.
 *
 * <p>Le cas qui compte, et qui n'existait pas avant ce lot : <b>un compte qui n'a
 * déclaré aucune activité</b>. C'est l'état de toute personne qui vient
 * d'installer l'application, et c'est exactement celui que les écrans de
 * découverte doivent savoir servir.
 */
class OnboardingIntegrationTest extends AbstractIntegrationTest {

    // Strasbourg — dans la zone où les migrations sèment des comptes.
    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    // Milieu du Pacifique Sud : aucune chance qu'un compte y soit semé.
    private static final double EMPTY_LAT = -40.0;
    private static final double EMPTY_LNG = -140.0;

    // — parcours d'accueil —

    @Test
    void unCompteNeuf_naPasCommenceSonAccueil() {
        String token = registerAndLogin();

        OnboardingStateDto state = getState(token);

        assertThat(state.step()).isNull();
        assertThat(state.completedAt()).isNull();
        assertThat(state.completed()).isFalse();
    }

    @Test
    void lEtatDeLAccueil_doitVoyagerAvecLeProfil() {
        // Sans cela le client paie un second aller-retour au lancement, juste pour
        // savoir sur quel écran atterrir — et ça se voit à l'œil nu.
        String token = registerAndLogin();
        advance(token, "LOCATION");

        UserPrivateDto profile = webTestClient.get()
            .uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult().getResponseBody();

        assertThat(profile).isNotNull();
        assertThat(profile.onboardingStep()).isEqualTo("LOCATION");
        assertThat(profile.onboardingCompletedAt()).isNull();
    }

    @Test
    void rejouerLaMemeEtape_neDoitPasEchouer() {
        // Le réseau mobile double les requêtes : un doublon n'est pas une erreur.
        String token = registerAndLogin();

        assertThat(advance(token, "ACTIVITIES").step()).isEqualTo("ACTIVITIES");
        assertThat(advance(token, "ACTIVITIES").step()).isEqualTo("ACTIVITIES");
    }

    @Test
    void uneEtapeAnterieure_neDoitPasFaireReculerLAccueil() {
        // Il ne double pas seulement les requêtes, il les livre parfois dans le
        // désordre. Une trame retardée ne doit pas renvoyer quelqu'un en arrière.
        String token = registerAndLogin();
        advance(token, "DISCOVERY");

        assertThat(advance(token, "WELCOME").step()).isEqualTo("DISCOVERY");
    }

    @Test
    void atteindreLaDerniereEtape_refermeLAccueil() {
        String token = registerAndLogin();

        OnboardingStateDto state = advance(token, "DONE");

        assertThat(state.completed()).isTrue();
        assertThat(state.completedAt()).isNotNull();
    }

    @Test
    void passerLAccueil_estAutorise_etConserveLEtapeAtteinte() {
        // L'étape atteinte est la seule information qu'apporte un abandon :
        // l'écraser par DONE effacerait où les gens décrochent.
        String token = registerAndLogin();
        advance(token, "LOCATION");

        OnboardingStateDto state = webTestClient.post()
            .uri("/api/users/me/onboarding/skip")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(OnboardingStateDto.class)
            .returnResult().getResponseBody();

        assertThat(state).isNotNull();
        assertThat(state.completed()).isTrue();
        assertThat(state.step()).isEqualTo("LOCATION");
    }

    // — suggestions —

    @Test
    void lesSuggestions_neDoiventJamaisEtreVides_pourUnCompteSansActivite() {
        // Garde-fou n°6. Une liste vide sur le premier écran raconte que le
        // service est mort, alors qu'elle dit seulement que personne n'habite là.
        String token = registerAndLogin();

        List<SuggestedActivityDto> suggestions = suggested(token, LAT, LNG, 12);

        assertThat(suggestions).isNotEmpty();
    }

    @Test
    void uneZoneVide_doitBasculerSurLeRepli_etLeDire() {
        String token = registerAndLogin();

        List<SuggestedActivityDto> suggestions = suggested(token, EMPTY_LAT, EMPTY_LNG, 12);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).allMatch(SuggestedActivityDto::fallback);
        // Un décompte de voisinage n'aurait aucun sens sur une proposition qui ne
        // vient pas du voisinage.
        assertThat(suggestions).allMatch(s -> s.practitionersNearby() == 0);
    }

    @Test
    void lesSuggestions_doiventCouvrirAuMoinsQuatreCategories() {
        String token = registerAndLogin();

        long cataloguedCategories = webTestClient.get()
            .uri("/api/categories")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(Map.class)
            .returnResult().getResponseBody().size();

        List<SuggestedActivityDto> suggestions = suggested(token, LAT, LNG, 12);

        long distinctCategories = suggestions.stream()
            .map(SuggestedActivityDto::categoryId)
            .distinct()
            .count();

        // Le plancher n'a de sens que si le catalogue peut le tenir.
        assertThat(distinctCategories)
            .isGreaterThanOrEqualTo(Math.min(4, cataloguedCategories));
    }

    @Test
    void uneActiviteDejaDeclaree_neDoitPasEtreProposee() {
        String token = registerAndLogin();

        List<SuggestedActivityDto> before = suggested(token, LAT, LNG, 12);
        assertThat(before).isNotEmpty();
        SuggestedActivityDto adopted = before.get(0);

        webTestClient.post()
            .uri("/api/users/me/activities")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("activityId", adopted.id().toString()))
            .exchange()
            .expectStatus().isCreated();

        assertThat(suggested(token, LAT, LNG, 12))
            .extracting(SuggestedActivityDto::id)
            .doesNotContain(adopted.id());
    }

    @Test
    void lesSuggestions_doiventRespecterLaLimiteDemandee() {
        String token = registerAndLogin();

        assertThat(suggested(token, LAT, LNG, 3)).hasSizeLessThanOrEqualTo(3);
    }

    // — helpers —

    private List<SuggestedActivityDto> suggested(String token, double lat, double lng, int limit) {
        return webTestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/activities/suggested")
                .queryParam("lat", lat)
                .queryParam("lng", lng)
                .queryParam("limit", limit)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(SuggestedActivityDto.class)
            .returnResult().getResponseBody();
    }

    private OnboardingStateDto getState(String token) {
        return webTestClient.get()
            .uri("/api/users/me/onboarding")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(OnboardingStateDto.class)
            .returnResult().getResponseBody();
    }

    private OnboardingStateDto advance(String token, String step) {
        return webTestClient.patch()
            .uri("/api/users/me/onboarding")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("step", step))
            .exchange()
            .expectStatus().isOk()
            .expectBody(OnboardingStateDto.class)
            .returnResult().getResponseBody();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("onboarding");
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Nouveau"))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse auth = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
