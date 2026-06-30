package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.map.dto.MapUserDto;
import org.program.pair.domain.user.dto.UpdateLocationRequest;
import org.program.pair.domain.user.dto.UpdateProfileRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MapVisibilityIntegrationTest — TEST CRITIQUE DE SÉCURITÉ ET VIE PRIVÉE
 *
 * Ce test valide le CŒUR du modèle de confiance de Pair :
 * - Respect de la vie privée (locationPublic)
 * - Sécurité des comptes désactivés
 * - Floutage géographique (anti-stalking)
 *
 * TOUS les tests doivent passer — aucun compromis sur la sécurité.
 */
class MapVisibilityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void utilisateurMasque_neDoitJamaisApparaitreSurLaCarte() {
        // 1. Créer deux utilisateurs proches géographiquement
        String tokenA = registerAndLogin("userA@pair.app");
        String tokenB = registerAndLogin("userB@pair.app");

        // 2. userB se positionne mais NE PAS activer locationPublic
        updateLocation(tokenB, 48.8566, 2.3522);
        updateProfile(tokenB, Map.of("locationPublic", false));

        // 3. userA active sa position et cherche autour de lui
        updateLocation(tokenA, 48.8567, 2.3523);
        updateProfile(tokenA, Map.of("locationPublic", true));

        List<MapUserDto> results = getMapUsers(tokenA, 48.8566, 2.3522, 5000);

        // userB ne doit JAMAIS apparaître
        assertThat(results).noneMatch(u -> u.displayName().equals("userB"));
    }

    @Test
    void compteDesactive_neDoitJamaisApparaitreSurLaCarte() {
        String tokenA = registerAndLogin("active@pair.app");
        String tokenB = registerAndLogin("supprime@pair.app");

        updateLocation(tokenB, 48.8566, 2.3522);
        updateProfile(tokenB, Map.of("locationPublic", true));
        deactivateAccount(tokenB);

        updateLocation(tokenA, 48.8567, 2.3523);
        List<MapUserDto> results = getMapUsers(tokenA, 48.8566, 2.3522, 5000);

        assertThat(results).noneMatch(u -> u.displayName().equals("supprime"));
    }

    @Test
    void utilisateurHorsRayon_neDoitPasApparaitre() {
        String tokenA = registerAndLogin("paris@pair.app");
        String tokenB = registerAndLogin("marseille@pair.app");

        updateLocation(tokenB, 43.2965, 5.3698); // Marseille
        updateProfile(tokenB, Map.of("locationPublic", true));

        updateLocation(tokenA, 48.8566, 2.3522); // Paris
        List<MapUserDto> results = getMapUsers(tokenA, 48.8566, 2.3522, 5000); // 5km

        assertThat(results).noneMatch(u -> u.displayName().equals("marseille"));
    }

    @Test
    void positionAffichee_doitEtreFlouttee_pasExacte() {
        String tokenA = registerAndLogin("observateur@pair.app");
        String tokenB = registerAndLogin("observe@pair.app");

        double exactLat = 48.85660000;
        double exactLng = 2.35220000;
        updateLocation(tokenB, exactLat, exactLng);
        updateProfile(tokenB, Map.of("locationPublic", true, "blurRadiusM", 500));

        updateLocation(tokenA, exactLat + 0.0001, exactLng + 0.0001);
        List<MapUserDto> results = getMapUsers(tokenA, exactLat, exactLng, 5000);

        MapUserDto observed = results.stream()
            .filter(u -> u.displayName().equals("observe")).findFirst().orElseThrow();

        // La position affichée ne doit JAMAIS être exactement la position réelle
        assertThat(observed.lat()).isNotEqualTo(exactLat);
        assertThat(observed.lng()).isNotEqualTo(exactLng);
    }

    // ==================== Helper Methods ====================

    /**
     * Enregistre un nouvel utilisateur et se connecte immédiatement.
     * Le displayName est déduit de l'email (partie avant @).
     *
     * @param email L'email de l'utilisateur
     * @return Le token d'accès JWT
     */
    private String registerAndLogin(String email) {
        // Extraire le displayName de l'email
        String displayName = email.substring(0, email.indexOf("@"));
        String password = "Password123!";

        // Enregistrement
        RegisterRequest registerReq = new RegisterRequest(email, password, displayName);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        // Login
        LoginRequest loginReq = new LoginRequest(email, password);
        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }

    /**
     * Met à jour la localisation géographique de l'utilisateur.
     *
     * @param token Token JWT de l'utilisateur
     * @param lat Latitude
     * @param lng Longitude
     */
    private void updateLocation(String token, double lat, double lng) {
        UpdateLocationRequest request = new UpdateLocationRequest(lat, lng);
        webTestClient.put()
            .uri("/api/users/me/location")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }

    /**
     * Met à jour le profil de l'utilisateur avec des champs dynamiques.
     *
     * @param token Token JWT de l'utilisateur
     * @param fields Map contenant les champs à mettre à jour (locationPublic, blurRadiusM, etc.)
     */
    private void updateProfile(String token, Map<String, Object> fields) {
        // Construire l'UpdateProfileRequest dynamiquement
        Map<String, Object> profileData = new HashMap<>(fields);

        // Construire le JSON manuellement pour supporter les champs optionnels
        UpdateProfileRequest request = new UpdateProfileRequest(
            (String) profileData.get("displayName"),
            (String) profileData.get("bio"),
            (Boolean) profileData.get("locationPublic"),
            (Boolean) profileData.get("onlineStatusVisible"),
            (Boolean) profileData.get("receiveMessages"),
            (Integer) profileData.get("blurRadiusM")
        );

        webTestClient.put()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }

    /**
     * Désactive le compte de l'utilisateur.
     *
     * @param token Token JWT de l'utilisateur
     */
    private void deactivateAccount(String token) {
        webTestClient.delete()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isNoContent();
    }

    /**
     * Récupère la liste des utilisateurs visibles sur la carte dans un rayon donné.
     *
     * @param token Token JWT de l'utilisateur qui effectue la recherche
     * @param lat Latitude du centre de recherche
     * @param lng Longitude du centre de recherche
     * @param radius Rayon de recherche en mètres
     * @return Liste des utilisateurs visibles
     */
    private List<MapUserDto> getMapUsers(String token, double lat, double lng, int radius) {
        List<MapUserDto> users = webTestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/map/users")
                .queryParam("lat", lat)
                .queryParam("lng", lng)
                .queryParam("radiusMeters", radius)
                .build())
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<List<MapUserDto>>() {})
            .returnResult()
            .getResponseBody();

        assertThat(users).isNotNull();
        return users;
    }
}
