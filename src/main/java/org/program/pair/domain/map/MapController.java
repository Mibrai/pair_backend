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
     * Marqueurs d'activité de la carte.
     *
     * <p>Chaque marqueur porte l'icône de catégorie, le nom, l'organisateur et,
     * si {@code userLat}/{@code userLng} sont fournis, la distance.
     *
     * <p>Le bornage est optionnel et additif : {@code radiusMeters} (avec la
     * position de l'utilisateur), une bbox {@code north}/{@code south}/{@code east}/{@code west},
     * et un {@code limit}. Voir {@link MapActivitiesRequest} pour les bornes et
     * les codes d'erreur.
     *
     * <p><b>Seules les activités ayant une séance à venir sont renvoyées</b>, et
     * la même règle vaut pour les membres des clusters : le {@code count} d'une
     * pastille est donc le nombre de marqueurs réellement affichables.
     * {@code includeExpired=true} rétablit la population d'avant ce filtre.
     *
     * <p><b>Authentifiée depuis le 2026-08-19.</b> Elle était ouverte sans jeton,
     * hérité d'un temps où la carte servait de vitrine. Le client ne l'appelle
     * depuis aucun de ses cinq écrans hors session — l'équipe mobile l'a vérifié
     * route par route — et sans identité d'appelant il n'y avait personne à qui
     * masquer les organisateurs bloqués. Le jour où une page web publique devra
     * afficher une carte, ce sera une route dédiée à cette page, pas celle-ci.
     *
     * @return marqueurs, centre par défaut, et l'état de troncature
     *         ({@code truncated}, {@code totalInBounds})
     */
    @GetMapping("/activities")
    public MapActivitiesResponse getAllActivitiesForMap(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute MapActivitiesRequest request) {
        return mapService.getAllActivitiesForMap(request, principal.getId());
    }
}
