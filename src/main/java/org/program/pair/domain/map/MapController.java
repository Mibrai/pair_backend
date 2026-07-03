package org.program.pair.domain.map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.map.dto.*;
import org.program.pair.domain.user.UserService;
import org.program.pair.domain.user.dto.UpdateLocationRequest;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
@Validated
public class MapController {

    private final MapService mapService;
    private final UserService userService;

    @GetMapping("/users")
    public List<MapUserDto> getUsersOnMap(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute MapSearchRequest request) {
        return mapService.getUsersOnMap(request, principal.getId());
    }

    @GetMapping("/clusters")
    public List<MapCluster> getClusters(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute MapClusterRequest request) {
        return mapService.getClusters(request, principal.getId());
    }

    @PostMapping("/location")
    public ResponseEntity<Void> updateLocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateLocationRequest request) {
        userService.updateLocation(principal.getId(), request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/bounds")
    public MapMarkersResponse getAllMarkersInBounds(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute MapBoundsRequest request) {
        return mapService.getAllMarkersInBounds(request, principal.getId());
    }

    @GetMapping("/nearby/{type}")
    public List<?> getNearbyItems(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String type,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "10") int radiusKm) {
        return mapService.getNearbyItems(type, lat, lng, radiusKm, principal.getId());
    }

    /**
     * Geocode: Convert address string to coordinates.
     *
     * @param address The address to geocode
     * @return GeocodingResult with coordinates and address details
     *
     * Note: This is currently a mock implementation. See MapService.geocode() for integration options.
     */
    @GetMapping("/geocode")
    public GeocodingResult geocode(@RequestParam String address) {
        return mapService.geocode(address);
    }

    /**
     * Reverse Geocode: Convert coordinates to address.
     *
     * @param latitude The latitude coordinate
     * @param longitude The longitude coordinate
     * @return GeocodingResult with address details
     *
     * Note: This is currently a mock implementation. See MapService.reverseGeocode() for integration options.
     */
    @GetMapping("/reverse-geocode")
    public GeocodingResult reverseGeocode(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        return mapService.reverseGeocode(latitude, longitude);
    }

    /**
     * Get all activities for the map page.
     * Returns all activities present in the database with their locations from schedules.
     * Each activity marker includes category icon, name, title, and distance if user location is provided.
     *
     * @param userLat User's latitude (optional - if geolocation enabled)
     * @param userLng User's longitude (optional - if geolocation enabled)
     * @return MapActivitiesResponse with all activity markers and default center
     */
    @GetMapping("/activities")
    public MapActivitiesResponse getAllActivitiesForMap(
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng) {
        return mapService.getAllActivitiesForMap(userLat, userLng);
    }
}
