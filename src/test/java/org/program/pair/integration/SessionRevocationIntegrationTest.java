package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.EmailVerificationService;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.auth.session.SessionService;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-03 — les sessions se révoquent, sans que personne soit déconnecté pour rien.
 *
 * <p>Les scénarios sont ceux de la vérification humaine de la fiche, rejoués par
 * l'API : déconnexion, rejeu, réponse perdue (P-BS/D4 option B), deux
 * rafraîchissements simultanés, mot de passe réinitialisé ou changé, purge.
 */
class SessionRevocationIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired EmailVerificationService emailVerificationService;
    @Autowired SessionService sessionService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void seDeconnecter_rendLeJetonDeRafraichissementInutilisable() {
        Compte compte = compte("session-logout");
        AuthResponse r2 = rafraichir(compte.connexion().refreshToken()).expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();

        deconnecter(r2.refreshToken()).expectStatus().isNoContent();

        rafraichir(r2.refreshToken()).expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
    }

    @Test
    void unRejeuApresUsageDuSuccesseur_revoqueToutLaSession() {
        Compte compte = compte("session-rejeu");
        String r1 = compte.connexion().refreshToken();
        String r2 = corps(rafraichir(r1)).refreshToken();
        String r3 = corps(rafraichir(r2)).refreshToken();

        rafraichir(r1).expectStatus().isUnauthorized();
        rafraichir(r3).expectStatus().isUnauthorized();
    }

    @Test
    void uneReponseDeRafraichissementPerdue_neDeconnectePas() {
        Compte compte = compte("session-perdue");
        String r1 = compte.connexion().refreshToken();
        corps(rafraichir(r1)); // R2 émis, jamais reçu par l'app

        String r2bis = corps(rafraichir(r1)).refreshToken();

        rafraichir(r2bis).expectStatus().isOk();
    }

    @Test
    void deuxRafraichissementsSimultanes_laissentUnSuccesseurValable_sansRevoquerLaSession() throws Exception {
        Compte compte = compte("session-simultane");
        String r1 = compte.connexion().refreshToken();
        CountDownLatch depart = new CountDownLatch(1);

        List<CompletableFuture<Integer>> essais = List.of(1, 2).stream()
            .map(i -> CompletableFuture.supplyAsync(() -> {
                try {
                    depart.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return rafraichir(r1).returnResult(AuthResponse.class).getStatus().value();
            }))
            .toList();
        depart.countDown();

        assertThat(essais.stream().map(CompletableFuture::join).toList()).containsOnly(200);
        // L'inscription et la connexion ont chacune ouvert une session : aucune ne
        // doit avoir été révoquée par la course.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_sessions WHERE user_id = ? AND revoked_at IS NOT NULL",
            Long.class, compte.id())).isZero();
    }

    @Test
    void reinitialiserSonMotDePasse_coupeToutesLesSessions_etLeJetonDAccesEnCours() {
        Compte compte = compte("session-reset");
        AuthResponse autreAppareil = connecter(compte.email());

        String jeton = emailVerificationService.generatePasswordResetToken(
            userRepository.findById(compte.id()).orElseThrow());
        webTestClient.post().uri("/api/auth/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("token", jeton, "newPassword", "NouveauMotDePasse1!"))
            .exchange().expectStatus().isOk();

        moi(compte.connexion().accessToken()).expectStatus().isUnauthorized();
        rafraichir(compte.connexion().refreshToken()).expectStatus().isUnauthorized();
        rafraichir(autreAppareil.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void changerSonMotDePasse_gardeLaSessionDeLAppareilQuiLAChange() {
        Compte compte = compte("session-change");
        AuthResponse cetAppareil = compte.connexion();
        AuthResponse autreAppareil = connecter(compte.email());

        webTestClient.post().uri("/api/users/me/change-password")
            .headers(h -> h.setBearerAuth(cetAppareil.accessToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("currentPassword", "Password123!", "newPassword", "NouveauMotDePasse1!"))
            .exchange().expectStatus().isOk();

        rafraichir(autreAppareil.refreshToken()).expectStatus().isUnauthorized();

        moi(cetAppareil.accessToken()).expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("TOKEN_EXPIRED");
        AuthResponse repare = corps(rafraichir(cetAppareil.refreshToken()));
        moi(repare.accessToken()).expectStatus().isOk();
    }

    /** P-BS-10 et P-BL-12 étape 3 — se déconnecter détache l'appareil : plus aucune push n'y part. */
    @Test
    void seDeconnecterAvecSonJetonDAppareil_leDetache() {
        Compte compte = compte("session-appareil");
        String jetonFcm = "fcm-" + UUID.randomUUID();
        webTestClient.post().uri("/api/notifications/devices")
            .headers(h -> h.setBearerAuth(compte.connexion().accessToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("token", jetonFcm, "platform", "IOS"))
            .exchange().expectStatus().is2xxSuccessful();

        webTestClient.post().uri("/api/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", compte.connexion().refreshToken(), "deviceToken", jetonFcm))
            .exchange().expectStatus().isNoContent();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE token = ?",
            Long.class, jetonFcm)).isZero();
    }

    @Test
    void laDeconnexion_repond204_sansJetonDAcces_etAvecUnJetonInconnu() {
        deconnecter("jeton-inconnu").expectStatus().isNoContent();
        webTestClient.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent();
    }

    @Test
    void chaqueEchange_repartDeTrenteJours() {
        Compte compte = compte("session-glissante");
        AuthResponse r2 = corps(rafraichir(compte.connexion().refreshToken()));

        Timestamp echeance = jdbcTemplate.queryForObject("""
            SELECT t.expires_at FROM refresh_tokens t JOIN refresh_sessions s ON s.id = t.session_id
             WHERE s.user_id = ? ORDER BY t.issued_at DESC LIMIT 1""", Timestamp.class, compte.id());

        assertThat(r2.refreshExpiresIn()).isEqualTo(2_592_000L);
        assertThat(echeance.toInstant())
            .isAfter(Instant.now().plus(29, ChronoUnit.DAYS))
            .isBefore(Instant.now().plus(31, ChronoUnit.DAYS));
    }

    @Test
    void laPurge_supprimeLesSessionsInactivesDepuisTrenteJours() {
        Compte compte = compte("session-purge");
        jdbcTemplate.update("UPDATE refresh_sessions SET last_used_at = ? WHERE user_id = ?",
            Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)), compte.id());

        sessionService.purger();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_sessions WHERE user_id = ?", Long.class, compte.id())).isZero();
        rafraichir(compte.connexion().refreshToken()).expectStatus().isUnauthorized();
    }

    // — décor —

    private record Compte(UUID id, String email, AuthResponse connexion) {}

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Session"))
            .exchange().expectStatus().isCreated();
        AuthResponse connexion = connecter(email);
        return new Compte(connexion.userId(), email, connexion);
    }

    private AuthResponse connecter(String email) {
        return webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec rafraichir(String jeton) {
        return webTestClient.post().uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", jeton))
            .exchange();
    }

    private WebTestClient.ResponseSpec deconnecter(String jeton) {
        return webTestClient.post().uri("/api/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", jeton))
            .exchange();
    }

    private WebTestClient.ResponseSpec moi(String acces) {
        return webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(acces))
            .exchange();
    }

    private static AuthResponse corps(WebTestClient.ResponseSpec reponse) {
        AuthResponse corps = reponse.expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(corps).isNotNull();
        return corps;
    }
}
