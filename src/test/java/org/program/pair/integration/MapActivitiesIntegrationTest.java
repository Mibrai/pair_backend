package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.map.dto.MapActivitiesResponse;
import org.program.pair.domain.map.dto.MapActivityMarkerDto;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MapActivitiesIntegrationTest
 *
 * Tests for the map activities endpoint that displays all activities on the map.
 */
class MapActivitiesIntegrationTest extends AbstractIntegrationTest {

    @Test
    void shouldReturnAllActivitiesWithoutUserLocation() {
        // Given: A logged-in user
        String token = registerAndLogin("testuser1@pair.app");

        // When: Fetching activities without providing user location
        MapActivitiesResponse response = webTestClient.get()
            .uri("/api/map/activities")
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();

        // Then: Should return activities with null distances
        assertThat(response).isNotNull();
        assertThat(response.activities()).isNotNull();
        assertThat(response.defaultCenter()).isNotNull();

        // All distances should be null when no user location provided
        for (MapActivityMarkerDto activity : response.activities()) {
            assertThat(activity.distanceKm()).isNull();
        }
    }

    @Test
    void shouldReturnActivitiesWithDistancesWhenUserLocationProvided() {
        // Given: A logged-in user
        String token = registerAndLogin("testuser2@pair.app");

        // When: Fetching activities with user location (Paris coordinates)
        double userLat = 48.8566;
        double userLng = 2.3522;

        MapActivitiesResponse response = webTestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/map/activities")
                .queryParam("userLat", userLat)
                .queryParam("userLng", userLng)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();

        // Then: Should return activities with calculated distances
        assertThat(response).isNotNull();
        assertThat(response.activities()).isNotNull();

        // At least some activities should have non-null distances
        List<MapActivityMarkerDto> activitiesWithDistance = response.activities().stream()
            .filter(a -> a.distanceKm() != null)
            .toList();

        if (!response.activities().isEmpty()) {
            assertThat(activitiesWithDistance).isNotEmpty();

            // Distances should be positive or zero
            for (MapActivityMarkerDto activity : activitiesWithDistance) {
                assertThat(activity.distanceKm()).isGreaterThanOrEqualTo(0.0);
            }
        }
    }

    @Test
    void shouldReturnDefaultCenterWhenNoActivities() {
        // Given: A logged-in user
        String token = registerAndLogin("testuser3@pair.app");

        // When: Fetching activities
        MapActivitiesResponse response = webTestClient.get()
            .uri("/api/map/activities")
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();

        // Then: Should always return a valid default center
        assertThat(response).isNotNull();
        assertThat(response.defaultCenter()).isNotNull();
        assertThat(response.defaultCenter().lat()).isBetween(-90.0, 90.0);
        assertThat(response.defaultCenter().lng()).isBetween(-180.0, 180.0);
        assertThat(response.defaultCenter().zoom()).isPositive();
    }

    /**
     * L'histoire de ce test tient en trois états, et vaut d'être conservée.
     *
     * <p>Il a d'abord exigé un {@code 401}, et il est resté rouge pendant tout le
     * temps où la route était ouverte sans jeton — personne n'ayant tranché en le
     * lisant. Il a ensuite été retourné pour verrouiller le contrat réel, avec la
     * réserve écrite que, sans appelant identifié, la route ne pouvait pas
     * masquer les organisateurs bloqués.
     *
     * <p>C'est cette réserve qui est levée le 2026-08-19 : l'équipe mobile a
     * vérifié qu'aucun des cinq écrans hors session n'appelle la carte, et la
     * route est refermée. Le premier état avait donc raison, pour une raison que
     * personne n'avait écrite à l'époque.
     *
     * <p>Le masquage lui-même est vérifié dans {@code UserBlockIntegrationTest} :
     * il demande deux comptes et un créneau publié, que cette classe n'a pas.
     */
    @Test
    void lesActivitesDeLaCarte_neDoiventPlusEtreLisiblesSansJeton() {
        webTestClient.get()
            .uri("/api/map/activities")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void shouldIncludeCategoryInformationInMarkers() {
        // Given: A logged-in user
        String token = registerAndLogin("testuser4@pair.app");

        // When: Fetching activities
        MapActivitiesResponse response = webTestClient.get()
            .uri("/api/map/activities")
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();

        // Then: Activities should have category information
        assertThat(response).isNotNull();

        if (!response.activities().isEmpty()) {
            for (MapActivityMarkerDto activity : response.activities()) {
                // Each activity should have basic information
                assertThat(activity.activityId()).isNotNull();
                assertThat(activity.activityName()).isNotBlank();
                assertThat(activity.activitySlug()).isNotBlank();
                assertThat(activity.lat()).isBetween(-90.0, 90.0);
                assertThat(activity.lng()).isBetween(-180.0, 180.0);
                assertThat(activity.programCount()).isPositive();

                // Category information (can be null for activities without category)
                // But if present, should be valid
                if (activity.categoryName() != null) {
                    assertThat(activity.categoryName()).isNotBlank();
                }
            }
        }
    }

    @Test
    void shouldIncludeOrganizerIdWheneverOrganizerNameIsPresent() {
        // Given: A logged-in user
        String token = registerAndLogin("testuser5@pair.app");

        // When: Fetching activities
        MapActivitiesResponse response = webTestClient.get()
            .uri("/api/map/activities")
            .headers(h -> h.setBearerAuth(token))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectBody(MapActivitiesResponse.class)
            .returnResult()
            .getResponseBody();

        // Then: organizerId must never be null when organizerName is present —
        // the mobile client needs it to link to the organizer's profile.
        assertThat(response).isNotNull();
        assertThat(response.activities()).isNotEmpty();

        List<MapActivityMarkerDto> withOrganizerName = response.activities().stream()
            .filter(a -> a.organizerName() != null)
            .toList();

        assertThat(withOrganizerName).isNotEmpty();
        for (MapActivityMarkerDto activity : withOrganizerName) {
            assertThat(activity.organizerId())
                .as("organizerId for activity %s (organizer '%s')", activity.activityId(), activity.organizerName())
                .isNotNull();
        }
    }

    // Helper method
    private String registerAndLogin(String email) {
        try {
            // Register
            var registerRequest = new RegisterRequest(
                email,
                "password123",
                "Test User"
            );

            webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registerRequest)
                .exchange()
                .expectStatus().is2xxSuccessful();

            // Login
            var loginRequest = new LoginRequest(
                email,
                "password123"
            );

            AuthResponse authResponse = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(loginRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

            return authResponse.accessToken();
        } catch (Exception e) {
            throw new RuntimeException("Failed to register and login", e);
        }
    }
}
