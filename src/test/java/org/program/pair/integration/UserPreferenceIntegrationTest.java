package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les réglages privés : une clé, une valeur opaque, un seul propriétaire.
 *
 * <p>Le test qui porte la propriété centrale est
 * {@link #uneePreferenceNestJamaisLisibleParQuelquunDautre()} : cet espace a été
 * construit à la place d'une relation d'amitié, précisément pour qu'une donnée
 * appartenant à une personne ne puisse pas devenir une information sur quelqu'un
 * d'autre. Si cette assertion tombe, la fonctionnalité a perdu sa raison d'être.
 */
class UserPreferenceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void poserPuisRelire_rendLaValeurTelleQuelle() {
        Compte moi = compte();
        String opaque = "{\"proches\":[\"a\",\"b\"],\"v\":2}";

        webTestClient.put().uri("/api/users/me/preferences/{k}", "amis")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("value", opaque))
            .exchange().expectStatus().isOk();

        webTestClient.get().uri("/api/users/me/preferences/{k}", "amis")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.value").isEqualTo(opaque);
    }

    @Test
    void poserDeuxFois_remplace() {
        Compte moi = compte();
        ecrire(moi, "tri", "ancien");
        ecrire(moi, "tri", "nouveau");

        webTestClient.get().uri("/api/users/me/preferences/{k}", "tri")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.value").isEqualTo("nouveau");
    }

    /**
     * La propriété qui justifie cette forme plutôt qu'une relation entre comptes :
     * la valeur de quelqu'un n'est lisible que par lui.
     */
    @Test
    void uneePreferenceNestJamaisLisibleParQuelquunDautre() {
        Compte moi = compte();
        Compte autre = compte();
        ecrire(moi, "amis", "secret");

        // Même clé, autre compte : chacun a la sienne, et celle du voisin n'existe pas.
        webTestClient.get().uri("/api/users/me/preferences/{k}", "amis")
            .headers(h -> h.setBearerAuth(autre.token()))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void uneCleJamaisPosee_rend404() {
        Compte moi = compte();
        webTestClient.get().uri("/api/users/me/preferences/{k}", "jamais-posee")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNotFound();
    }

    /** Effacer est idempotent : effacer une clé absente réussit. */
    @Test
    void effacerEstIdempotent() {
        Compte moi = compte();
        ecrire(moi, "tri", "x");

        for (int i = 0; i < 2; i++) {
            webTestClient.delete().uri("/api/users/me/preferences/{k}", "tri")
                .headers(h -> h.setBearerAuth(moi.token()))
                .exchange().expectStatus().isNoContent();
        }
        webTestClient.get().uri("/api/users/me/preferences/{k}", "tri")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNotFound();
    }

    /**
     * Une clé est un identifiant technique du client, jamais une saisie : bornée
     * pour qu'elle ne puisse ni transporter de contenu, ni ressembler à un chemin.
     */
    @Test
    void uneCleHorsAlphabet_estRefusee() {
        Compte moi = compte();
        webTestClient.put().uri("/api/users/me/preferences/{k}", "clé avec espace")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("value", "x"))
            .exchange().expectStatus().is4xxClientError();
    }

    @Test
    void uneValeurTropLongue_estRefusee() {
        Compte moi = compte();
        webTestClient.put().uri("/api/users/me/preferences/{k}", "gros")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("value", "x".repeat(8193)))
            .exchange().expectStatus().is4xxClientError();
    }

    @Test
    void sansJeton_rienNestLisible() {
        webTestClient.get().uri("/api/users/me/preferences/{k}", "amis")
            .exchange().expectStatus().is4xxClientError();
    }

    // ------------------------------------------------------------------ outils

    private void ecrire(Compte owner, String key, String value) {
        webTestClient.put().uri("/api/users/me/preferences/{k}", key)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("value", value))
            .exchange().expectStatus().isOk();
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("pref");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Pref" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();

        UUID id = UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(auth.accessToken()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        return new Compte(id, auth.accessToken());
    }
}
