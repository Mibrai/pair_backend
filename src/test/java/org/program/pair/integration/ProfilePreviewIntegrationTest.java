package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D4 — aperçu du profil public.
 *
 * <p>Le test central compare l'aperçu à ce qu'un inconnu reçoit réellement. Il
 * n'aurait rien prouvé avant ce lot : les réglages de confidentialité étaient
 * stockés, réglables, et appliqués nulle part, si bien que les deux sorties
 * étaient identiques quoi qu'on règle.
 */
class ProfilePreviewIntegrationTest extends AbstractIntegrationTest {

    // — l'aperçu dit vrai —

    @Test
    void lApercu_doitCoinciderAvecCeQuUnInconnuRecoit() {
        String me = registerAndLogin("Camille");
        UUID myId = userId(me);
        updateProfile(me, Map.of("bio", "Je cours le dimanche"));

        String stranger = registerAndLogin("Inconnu");

        UserPublicDto preview = preview(me);
        UserPublicDto seenByStranger = publicProfile(stranger, myId);

        assertThat(preview).isEqualTo(seenByStranger);
    }

    @Test
    void lApercu_doitSuivreUnProfilRenduPrive() {
        String me = registerAndLogin("Camille");
        UUID myId = userId(me);
        updateProfile(me, Map.of("bio", "Je cours le dimanche"));

        assertThat(preview(me).bio()).isEqualTo("Je cours le dimanche");

        setVisibility(me, "PRIVATE");

        String stranger = registerAndLogin("Inconnu");
        assertThat(preview(me)).isEqualTo(publicProfile(stranger, myId));
        assertThat(preview(me).bio()).isNull();
    }

    // — les réglages, enfin appliqués —

    @Test
    void unProfilPrive_doitMasquerLaFiche_maisResterIdentifiable() {
        // Nom, avatar et badge de vérification restent visibles : ce sont les
        // éléments par lesquels une personne est reconnue dans une conversation
        // ou sur une liste de participants. Les masquer casserait l'application
        // sans protéger personne.
        String me = registerAndLogin("Camille");
        UUID myId = userId(me);
        updateProfile(me, Map.of("bio", "Je cours le dimanche"));
        setVisibility(me, "PRIVATE");

        UserPublicDto seen = publicProfile(registerAndLogin("Inconnu"), myId);

        assertThat(seen.displayName()).isEqualTo("Camille");
        assertThat(seen.verificationStatus()).isNotNull();
        assertThat(seen.bio()).isNull();
        assertThat(seen.badgeCodes()).isEmpty();
        assertThat(seen.subscriberCount()).isNull();
        assertThat(seen.reliabilitySignal()).isNull();
        assertThat(seen.isOnline()).isFalse();
    }

    @Test
    void unProfilPublic_neDoitRienMasquer() {
        // La valeur par défaut : personne n'est affecté tant qu'il n'a rien réglé.
        String me = registerAndLogin("Camille");
        UUID myId = userId(me);
        updateProfile(me, Map.of("bio", "Je cours le dimanche"));

        UserPublicDto seen = publicProfile(registerAndLogin("Inconnu"), myId);

        assertThat(seen.bio()).isEqualTo("Je cours le dimanche");
        assertThat(seen.subscriberCount()).isNotNull();
    }

    @Test
    void unProfilReserveAuxAbonnes_doitSOuvrirALAbonne_etPasAuxAutres() {
        // meetDo n'a pas de notion d'amitié : l'abonnement est le seul lien
        // explicite entre deux personnes, et c'est donc lui qui fait foi.
        String me = registerAndLogin("Camille");
        UUID myId = userId(me);
        updateProfile(me, Map.of("bio", "Je cours le dimanche"));
        setVisibility(me, "FRIENDS");

        String stranger = registerAndLogin("Inconnu");
        assertThat(publicProfile(stranger, myId).bio()).isNull();

        String follower = registerAndLogin("Abonne");
        webTestClient.post().uri("/api/users/{id}/subscription", myId)
            .headers(h -> h.setBearerAuth(follower))
            .exchange().expectStatus().is2xxSuccessful();

        assertThat(publicProfile(follower, myId).bio()).isEqualTo("Je cours le dimanche");
    }

    @Test
    void lApercu_dUnProfilReserveAuxAbonnes_doitMontrerLaVueLaPlusRestrictive() {
        // Quelqu'un qui règle « abonnés seulement » doit voir ce que voit un
        // inconnu, pas ce que voit son abonné : c'est la vue qui l'intéresse.
        String me = registerAndLogin("Camille");
        updateProfile(me, Map.of("bio", "Je cours le dimanche"));
        setVisibility(me, "FRIENDS");

        assertThat(preview(me).bio()).isNull();
    }

    // — helpers —

    private UserPublicDto preview(String token) {
        return webTestClient.get().uri("/api/users/me/preview")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(UserPublicDto.class).returnResult().getResponseBody();
    }

    private UserPublicDto publicProfile(String token, UUID targetId) {
        return webTestClient.get().uri("/api/users/{id}", targetId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(UserPublicDto.class).returnResult().getResponseBody();
    }

    private void updateProfile(String token, Map<String, Object> body) {
        webTestClient.put().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().is2xxSuccessful();
    }

    private void setVisibility(String token, String visibility) {
        webTestClient.put().uri("/api/users/me/privacy")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("profileVisibility", visibility))
            .exchange().expectStatus().is2xxSuccessful();
    }

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private String registerAndLogin(String displayName) {
        String email = uniqueEmail("preview");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", displayName))
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
