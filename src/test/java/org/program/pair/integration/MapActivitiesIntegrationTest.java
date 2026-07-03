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

    @Test
    void shouldRequireAuthentication() {
        // When: Trying to access without authentication
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
