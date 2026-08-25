package org.program.pair.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérification d'adresse e-mail — ticket client du 2026-08-25.
 *
 * <p>Le lien envoyé pointait sur {@code localhost:3000}, et les jetons vivaient
 * en mémoire. Ces tests verrouillent les deux corrections, plus la distinction
 * des quatre états que la page doit rendre.
 */
class EmailVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailVerificationService emailVerificationService;

    /**
     * Aplatit les blancs du HTML rendu.
     *
     * <p>Le gabarit coupe ses phrases sur plusieurs lignes pour rester lisible.
     * Sans cela, une assertion sur une phrase entière échouerait au premier
     * reformatage du gabarit — un test qui casse pour une raison qui n'est pas
     * celle qu'il vérifie.
     */
    private static String texte(String html) {
        return html == null ? "" : html.replaceAll("\\s+", " ");
    }

    private String inscrire(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", "MotDePasse1!", "displayName", "Testeur"))
            .exchange()
            .expectStatus().isCreated();
        return email;
    }

    private String jetonDe(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return authTokenRepository.findAll().stream()
            .filter(t -> t.getUser().getId().equals(user.getId()))
            .filter(t -> t.getType() == AuthTokenType.EMAIL_VERIFICATION)
            .findFirst().orElseThrow().getToken();
    }

    @Test
    @DisplayName("l'inscription pose un jeton en base, pas seulement en mémoire")
    void jetonPersiste() {
        String email = uniqueEmail("persistance");
        inscrire(email);

        User user = userRepository.findByEmail(email).orElseThrow();
        // C'est le cœur du correctif : avant, rien n'atteignait la base et un
        // redéploiement effaçait le jeton sans laisser de trace.
        assertThat(authTokenRepository.findAll())
            .anySatisfy(t -> {
                assertThat(t.getUser().getId()).isEqualTo(user.getId());
                assertThat(t.getType()).isEqualTo(AuthTokenType.EMAIL_VERIFICATION);
                assertThat(t.getConsumedAt()).isNull();
                assertThat(t.getExpiresAt()).isAfter(Instant.now());
            });
    }

    @Test
    @DisplayName("un navigateur reçoit une page HTML, pas du JSON")
    void navigateurRecoitDuHtml() {
        String email = uniqueEmail("navigateur");
        inscrire(email);

        String corps = webTestClient.get()
            .uri("/api/auth/verify-email?token=" + jetonDe(email))
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("Votre adresse est vérifiée");
        // Le pire cas du ticket : une réussite qui ressemble à une panne.
        assertThat(texte(corps)).doesNotContain("{\"");

        assertThat(userRepository.findByEmail(email).orElseThrow().getVerificationStatus())
            .isEqualTo(VerificationStatus.EMAIL_VERIFIED);
    }

    @Test
    @DisplayName("un second clic dit « déjà vérifiée », et non « lien inconnu »")
    void secondClicNeRessembblePasAUnePanne() {
        String email = uniqueEmail("second-clic");
        inscrire(email);
        String jeton = jetonDe(email);

        webTestClient.get().uri("/api/auth/verify-email?token=" + jeton)
            .accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk();

        String corps = webTestClient.get().uri("/api/auth/verify-email?token=" + jeton)
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("déjà vérifiée");
        assertThat(texte(corps)).doesNotContain("n'est pas reconnu");
    }

    @Test
    @DisplayName("un jeton échu est distingué d'un jeton inconnu")
    void jetonEchuDistingue() {
        String email = uniqueEmail("echu");
        inscrire(email);

        AuthToken jeton = authTokenRepository.findByTokenAndType(
            jetonDe(email), AuthTokenType.EMAIL_VERIFICATION).orElseThrow();
        jeton.setExpiresAt(Instant.now().minusSeconds(60));
        authTokenRepository.save(jeton);

        String corps = webTestClient.get().uri("/api/auth/verify-email?token=" + jeton.getToken())
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("a expiré");
        assertThat(texte(corps)).contains("nouvel e-mail de vérification");
    }

    @Test
    @DisplayName("un jeton inconnu rend une page, pas une trace d'erreur")
    void jetonInconnuRendUnePage() {
        String corps = webTestClient.get().uri("/api/auth/verify-email?token=nexiste-pas")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("n'est pas reconnu");
    }

    @Test
    @DisplayName("le contrat JSON de l'app mobile est inchangé")
    void contratJsonInchange() {
        String email = uniqueEmail("contrat-json");
        inscrire(email);

        webTestClient.get().uri("/api/auth/verify-email?token=" + jetonDe(email))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk();

        // Un jeton invalide reste un 401 INVALID_TOKEN — c'est ce que le client
        // a mesuré depuis l'extérieur, et ce sur quoi l'app s'appuie.
        webTestClient.get().uri("/api/auth/verify-email?token=nexiste-pas")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("émettre un nouveau jeton referme le précédent")
    void nouveauJetonFermeLePrecedent() {
        String email = uniqueEmail("renvoi");
        inscrire(email);
        String premier = jetonDe(email);

        User user = userRepository.findByEmail(email).orElseThrow();
        emailVerificationService.sendVerificationEmail(user);

        assertThat(authTokenRepository.findByTokenAndType(premier, AuthTokenType.EMAIL_VERIFICATION)
            .orElseThrow().getConsumedAt())
            .as("deux liens actifs pour une même adresse laisseraient l'utilisateur choisir au hasard")
            .isNotNull();
    }

    @Test
    @DisplayName("le renvoi ne révèle pas si une adresse est inscrite")
    void renvoiNeRevelePasLesAdresses() {
        webTestClient.post().uri("/api/auth/resend-verification")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", "personne-ici@pair.app"))
            .exchange()
            .expectStatus().isOk();
    }
}
