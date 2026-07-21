package org.program.pair.domain.map;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.map.dto.*;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MapService {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final ActivityRepository activityRepository;
    private final ScheduleRepository scheduleRepository;
    private final ProgramRepository programRepository;
    private final UserProgramRepository userProgramRepository;
    private final Random random = new Random();

    public List<MapUserDto> getUsersOnMap(MapSearchRequest request, UUID requesterId) {
        // 1. Find visible users in radius
        List<User> nearbyUsers = userRepository.findVisibleUsersInRadius(
            request.lat(), request.lng(), request.radiusMeters(), 100, 0);

        // 2. Filter by activity if requested
        if (request.activityId() != null) {
            Set<UUID> userIdsWithActivity = userActivityRepository
                .findUserIdsByActivityIdAndVisible(request.activityId());
            nearbyUsers = nearbyUsers.stream()
                .filter(u -> userIdsWithActivity.contains(u.getId()))
                .toList();
        }

        // 3. Never return the requester themselves
        return nearbyUsers.stream()
            .filter(u -> !u.getId().equals(requesterId))
            .map(u -> toMapDto(u, request.activityId()))
            .filter(dto -> dto != null)  // Filter out users without location
            .toList();
    }

    private MapUserDto toMapDto(User user, UUID filterActivityId) {
        // Skip users without location
        if (user.getLocation() == null) {
            return null;
        }

        // Apply position blurring
        double[] blurred = applyBlur(
            user.getLocation().getY(),
            user.getLocation().getX(),
            user.getBlurRadiusM()
        );

        boolean isOnline = Boolean.TRUE.equals(user.getOnlineStatusVisible())
            && user.getLastActiveAt() != null
            && user.getLastActiveAt().isAfter(Instant.now().minusSeconds(300));

        List<MapActivityBadgeDto> activities = userActivityRepository
            .findVisibleByUserId(user.getId()).stream()
            .filter(ua -> filterActivityId == null
                || ua.getActivity().getId().equals(filterActivityId))
            .map(this::toActivityBadge)
            .toList();

        return new MapUserDto(
            user.getId(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            blurred[0],
            blurred[1],
            isOnline,
            activities,
            user.getVerificationStatus().name()
        );
    }

    private MapActivityBadgeDto toActivityBadge(UserActivity ua) {
        return new MapActivityBadgeDto(
            ua.getActivity().getId(),
            ua.getActivity().getName(),
            ua.getLevel() != null ? ua.getLevel().name() : null,
            ua.getFormat() != null ? ua.getFormat().name() : null,
            ua.getActivity().getCategory() != null
                ? ua.getActivity().getCategory().getColorRamp()
                : null
        );
    }

    public List<MapCluster> getClusters(MapClusterRequest request, UUID requesterId) {
        // Calculate bounds center and radius for query
        double centerLat = (request.north() + request.south()) / 2.0;
        double centerLng = (request.east() + request.west()) / 2.0;

        // Calculate radius from bounds (in meters)
        double latDiff = Math.abs(request.north() - request.south());
        double lngDiff = Math.abs(request.east() - request.west());
        int radiusMeters = (int) (Math.max(latDiff, lngDiff) * 111320.0 / 2.0);

        // Cap radius at 50km for performance
        radiusMeters = Math.min(radiusMeters, 50000);

        // 1. Find visible users in bounds
        List<User> nearbyUsers = userRepository.findVisibleUsersInRadius(
            centerLat, centerLng, radiusMeters, 1000, 0);

        // 2. Filter by activity if requested
        if (request.activityId() != null) {
            Set<UUID> userIdsWithActivity = userActivityRepository
                .findUserIdsByActivityIdAndVisible(request.activityId());
            nearbyUsers = nearbyUsers.stream()
                .filter(u -> userIdsWithActivity.contains(u.getId()))
                .toList();
        }

        // 3. Filter out requester and users without location
        List<User> validUsers = nearbyUsers.stream()
            .filter(u -> !u.getId().equals(requesterId))
            .filter(u -> u.getLocation() != null)
            .toList();

        // 4. Apply grid-based clustering
        return clusterUsers(validUsers, request.zoom());
    }

    private List<MapCluster> clusterUsers(List<User> users, int zoom) {
        // Calculate grid cell size based on zoom level
        // Higher zoom = smaller cells = less clustering
        // Zoom 1-5: very aggressive clustering (large cells)
        // Zoom 6-10: moderate clustering
        // Zoom 11-15: light clustering
        // Zoom 16-20: minimal/no clustering
        double gridSize = calculateGridSize(zoom);

        // Group users by grid cell
        Map<String, List<User>> grid = users.stream()
            .collect(Collectors.groupingBy(user -> {
                double lat = user.getLocation().getY();
                double lng = user.getLocation().getX();
                int gridLat = (int) Math.floor(lat / gridSize);
                int gridLng = (int) Math.floor(lng / gridSize);
                return gridLat + "," + gridLng;
            }));

        // Create clusters from grid cells
        return grid.entrySet().stream()
            .map(entry -> {
                List<User> cellUsers = entry.getValue();

                // Calculate cluster center (average of all user positions)
                double avgLat = cellUsers.stream()
                    .mapToDouble(u -> u.getLocation().getY())
                    .average()
                    .orElse(0.0);

                double avgLng = cellUsers.stream()
                    .mapToDouble(u -> u.getLocation().getX())
                    .average()
                    .orElse(0.0);

                // Determine cluster type based on size
                String type = cellUsers.size() == 1 ? "single" : "cluster";

                return new MapCluster(
                    Math.round(avgLat * 10000.0) / 10000.0,
                    Math.round(avgLng * 10000.0) / 10000.0,
                    cellUsers.size(),
                    type
                );
            })
            .toList();
    }

    private double calculateGridSize(int zoom) {
        // Grid size in degrees (latitude/longitude)
        // Higher zoom = smaller grid cells
        return switch (zoom) {
            case 1, 2, 3 -> 5.0;      // Very coarse: ~500km cells
            case 4, 5 -> 2.0;          // Coarse: ~200km cells
            case 6, 7 -> 1.0;          // Medium: ~100km cells
            case 8, 9 -> 0.5;          // Medium-fine: ~50km cells
            case 10, 11 -> 0.25;       // Fine: ~25km cells
            case 12, 13 -> 0.1;        // Very fine: ~10km cells
            case 14, 15 -> 0.05;       // Ultra fine: ~5km cells
            case 16, 17 -> 0.02;       // Super fine: ~2km cells
            case 18, 19, 20 -> 0.01;   // Minimal clustering: ~1km cells
            default -> 1.0;
        };
    }

    // Random blur within a circle of radius blur_radius_m
    // Simplified geodesic displacement formula
    private double[] applyBlur(double lat, double lng, int radiusMeters) {
        double radiusDeg = radiusMeters / 111320.0;
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = random.nextDouble() * radiusDeg;
        double blurredLat = lat + distance * Math.cos(angle);
        double blurredLng = lng + distance * Math.sin(angle)
            / Math.cos(Math.toRadians(lat));
        return new double[]{
            Math.round(blurredLat * 10000.0) / 10000.0,
            Math.round(blurredLng * 10000.0) / 10000.0
        };
    }

    public List<MapUserDto> getUsersInBounds(MapBoundsRequest request, UUID requesterId) {
        // Calculate bounds center and radius for query
        double centerLat = (request.north() + request.south()) / 2.0;
        double centerLng = (request.east() + request.west()) / 2.0;

        // Calculate radius from bounds (in meters)
        double latDiff = Math.abs(request.north() - request.south());
        double lngDiff = Math.abs(request.east() - request.west());
        int radiusMeters = (int) (Math.max(latDiff, lngDiff) * 111320.0 / 2.0);

        // Cap radius at 100km for performance
        radiusMeters = Math.min(radiusMeters, 100000);

        // Find visible users in radius
        List<User> nearbyUsers = userRepository.findVisibleUsersInRadius(
            centerLat, centerLng, radiusMeters, request.limit(), request.offset());

        // Filter by bounds precisely
        nearbyUsers = nearbyUsers.stream()
            .filter(u -> u.getLocation() != null
                && u.getLocation().getY() >= request.south()
                && u.getLocation().getY() <= request.north()
                && u.getLocation().getX() >= request.west()
                && u.getLocation().getX() <= request.east())
            .toList();

        // Filter by activity levels if provided
        if (request.activityLevels() != null && !request.activityLevels().isEmpty()) {
            Set<UUID> userIds = userActivityRepository.findVisibleByUserId(requesterId).stream()
                .filter(ua -> request.activityLevels().contains(ua.getLevel().name()))
                .map(ua -> ua.getUser().getId())
                .collect(Collectors.toSet());
            nearbyUsers = nearbyUsers.stream()
                .filter(u -> userIds.contains(u.getId()))
                .toList();
        }

        // Never return the requester themselves
        return nearbyUsers.stream()
            .filter(u -> !u.getId().equals(requesterId))
            .map(u -> toMapDto(u, null))
            .filter(dto -> dto != null)
            .toList();
    }

    public List<MapActivityDto> getActivitiesInBounds(MapBoundsRequest request) {
        // Get all activities (activities don't have location, but users with activities do)
        // So we find users in bounds who have activities, then aggregate by unique activities
        double centerLat = (request.north() + request.south()) / 2.0;
        double centerLng = (request.east() + request.west()) / 2.0;
        double latDiff = Math.abs(request.north() - request.south());
        double lngDiff = Math.abs(request.east() - request.west());
        int radiusMeters = Math.min((int) (Math.max(latDiff, lngDiff) * 111320.0 / 2.0), 100000);

        List<User> nearbyUsers = userRepository.findVisibleUsersInRadius(
            centerLat, centerLng, radiusMeters, 1000, 0);

        // Filter by bounds
        nearbyUsers = nearbyUsers.stream()
            .filter(u -> u.getLocation() != null
                && u.getLocation().getY() >= request.south()
                && u.getLocation().getY() <= request.north()
                && u.getLocation().getX() >= request.west()
                && u.getLocation().getX() <= request.east())
            .toList();

        // Get unique activities from these users
        Map<UUID, List<User>> activityUserMap = new HashMap<>();
        for (User user : nearbyUsers) {
            List<UserActivity> userActivities = userActivityRepository.findVisibleByUserId(user.getId());
            for (UserActivity ua : userActivities) {
                activityUserMap.computeIfAbsent(ua.getActivity().getId(), k -> new ArrayList<>()).add(user);
            }
        }

        // Convert to DTOs with representative location (first user's location)
        return activityUserMap.entrySet().stream()
            .map(entry -> {
                UUID activityId = entry.getKey();
                List<User> users = entry.getValue();
                if (users.isEmpty()) return null;

                Activity activity = activityRepository.findById(activityId).orElse(null);
                if (activity == null) return null;

                // Use first user's location as representative
                User firstUser = users.get(0);
                if (firstUser.getLocation() == null) return null;

                return new MapActivityDto(
                    activity.getId(),
                    activity.getName(),
                    activity.getSlug(),
                    activity.getDescription(),
                    activity.getCategory() != null ? activity.getCategory().getName() : null,
                    activity.getCategory() != null ? activity.getCategory().getColorRamp() : null,
                    firstUser.getLocation().getY(),
                    firstUser.getLocation().getX()
                );
            })
            .filter(dto -> dto != null)
            .limit(request.limit())
            .toList();
    }

    public List<MapProgramDto> getProgramsInBounds(MapBoundsRequest request) {
        // Programs have schedules with locations
        // Get schedules within bounds
        List<Schedule> allSchedules = scheduleRepository.findAll();

        List<Schedule> schedulesInBounds = allSchedules.stream()
            .filter(s -> s.getLocation() != null
                && s.getLocation().getY() >= request.south()
                && s.getLocation().getY() <= request.north()
                && s.getLocation().getX() >= request.west()
                && s.getLocation().getX() <= request.east())
            .toList();

        // Filter by category if provided
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            schedulesInBounds = schedulesInBounds.stream()
                .filter(s -> {
                    Program program = s.getProgram();
                    if (program == null || program.getUserActivity() == null) return false;
                    Activity activity = program.getUserActivity().getActivity();
                    return activity != null
                        && activity.getCategory() != null
                        && request.categoryIds().contains(activity.getCategory().getId());
                })
                .toList();
        }

        // Convert to DTOs
        return schedulesInBounds.stream()
            .map(schedule -> {
                Program program = schedule.getProgram();
                if (program == null || program.getUserActivity() == null) return null;

                UserActivity userActivity = program.getUserActivity();
                Activity activity = userActivity.getActivity();
                User organizer = userActivity.getUser();

                return new MapProgramDto(
                    program.getId(),
                    program.getTitle(),
                    program.getDescription(),
                    activity != null ? activity.getName() : null,
                    activity != null && activity.getCategory() != null
                        ? activity.getCategory().getColorRamp() : null,
                    schedule.getPlaceName(),
                    schedule.getShowExactAddress() ? schedule.getAddressPublic() : null,
                    schedule.getLocation().getY(),
                    schedule.getLocation().getX(),
                    schedule.getStartsAt(),
                    schedule.getEndsAt(),
                    schedule.getMaxParticipants(),
                    organizer != null ? organizer.getDisplayName() : null,
                    organizer != null ? organizer.getId() : null
                );
            })
            .filter(dto -> dto != null)
            .skip(request.offset())
            .limit(request.limit())
            .toList();
    }

    public List<?> getNearbyItems(String type, double lat, double lng, int radiusKm, UUID requesterId) {
        int radiusMeters = radiusKm * 1000;

        return switch (type.toLowerCase()) {
            case "users" -> {
                List<User> users = userRepository.findVisibleUsersInRadius(
                    lat, lng, radiusMeters, 100, 0);
                yield users.stream()
                    .filter(u -> !u.getId().equals(requesterId))
                    .map(u -> toMapDto(u, null))
                    .filter(dto -> dto != null)
                    .toList();
            }
            case "activities" -> {
                List<UserActivity> userActivities = userActivityRepository.findVisibleInRadius(
                    lat, lng, radiusMeters, 100);

                // Group by activity and use first user's location
                Map<UUID, UserActivity> activityMap = new HashMap<>();
                for (UserActivity ua : userActivities) {
                    activityMap.putIfAbsent(ua.getActivity().getId(), ua);
                }

                yield activityMap.values().stream()
                    .filter(ua -> ua.getUser().getLocation() != null)
                    .map(ua -> {
                        User user = ua.getUser();
                        Activity activity = ua.getActivity();
                        return new MapActivityDto(
                            activity.getId(),
                            activity.getName(),
                            activity.getSlug(),
                            activity.getDescription(),
                            activity.getCategory() != null ? activity.getCategory().getName() : null,
                            activity.getCategory() != null ? activity.getCategory().getColorRamp() : null,
                            user.getLocation().getY(),
                            user.getLocation().getX()
                        );
                    })
                    .toList();
            }
            case "programs" -> {
                List<Program> programs = programRepository.findVisibleInRadius(
                    lat, lng, radiusMeters, 100);

                yield programs.stream()
                    .filter(p -> p.getUserActivity() != null
                        && p.getUserActivity().getUser() != null
                        && p.getUserActivity().getUser().getLocation() != null)
                    .map(program -> {
                        UserActivity userActivity = program.getUserActivity();
                        User organizer = userActivity.getUser();
                        Activity activity = userActivity.getActivity();
                        long enrollmentCount = userProgramRepository
                            .countActiveParticipantsByProgramId(program.getId());

                        return new MapProgramDto(
                            program.getId(),
                            program.getTitle(),
                            program.getDescription(),
                            activity != null ? activity.getName() : null,
                            activity != null && activity.getCategory() != null
                                ? activity.getCategory().getColorRamp() : null,
                            null,  // placeName (from schedule)
                            null,  // addressPublic (from schedule)
                            organizer.getLocation().getY(),
                            organizer.getLocation().getX(),
                            null,  // startsAt (from schedule)
                            null,  // endsAt (from schedule)
                            null,  // maxParticipants (from schedule)
                            organizer.getDisplayName(),
                            organizer.getId()
                        );
                    })
                    .toList();
            }
            default -> throw new IllegalArgumentException("Invalid type: " + type + ". Must be users, activities, or programs");
        };
    }

    public MapMarkersResponse getAllMarkersInBounds(MapBoundsRequest request, UUID requesterId) {
        List<MapUserDto> users = getUsersInBounds(request, requesterId);
        List<MapActivityDto> activities = getActivitiesInBounds(request);
        List<MapProgramDto> programs = getProgramsInBounds(request);

        return new MapMarkersResponse(users, activities, programs);
    }

    /**
     * Geocode: Convert address string to coordinates.
     *
     * TODO: Integrate with a real geocoding service:
     * - Option A: Google Maps Geocoding API (requires API key and billing setup)
     * - Option B: Nominatim (OpenStreetMap - free, rate-limited)
     * - Option C: Mapbox Geocoding API (requires API key)
     *
     * This is a mock implementation returning placeholder data.
     */
    public GeocodingResult geocode(String address) {
        // Mock implementation - returns fixed placeholder coordinates for any address
        return new GeocodingResult(
            48.8566,  // Paris latitude (placeholder)
            2.3522,   // Paris longitude (placeholder)
            address,  // Echo back the input address
            "Mock City",
            "Mock Country"
        );
    }

    /**
     * Reverse Geocode: Convert coordinates to address.
     *
     * TODO: Integrate with a real reverse geocoding service:
     * - Option A: Google Maps Reverse Geocoding API (requires API key and billing setup)
     * - Option B: Nominatim (OpenStreetMap - free, rate-limited)
     * - Option C: Mapbox Reverse Geocoding API (requires API key)
     *
     * This is a mock implementation returning placeholder data.
     */
    public GeocodingResult reverseGeocode(double latitude, double longitude) {
        // Mock implementation - returns placeholder address for any coordinates
        return new GeocodingResult(
            latitude,
            longitude,
            String.format("Mock Address at %.4f, %.4f", latitude, longitude),
            "Mock City",
            "Mock Country"
        );
    }

    /**
     * Get all activities present in the database with their locations from schedules.
     * Each activity is shown on the map with a badge containing category icon, name, title, and distance.
     *
     * @param userLat User's latitude (nullable if geolocation not enabled)
     * @param userLng User's longitude (nullable if geolocation not enabled)
     * @return MapActivitiesResponse with all activity markers and default center
     */
    public MapActivitiesResponse getAllActivitiesForMap(Double userLat, Double userLng) {
        try {
            // 1. Get all activities from database
            List<Activity> allActivities = activityRepository.findAll();

            // 2. Get all schedules with locations and eagerly fetch related entities
            List<Schedule> allSchedules = scheduleRepository.findAllWithActivityDetails();

        // 3. Build a map of activity -> list of schedule locations
        Map<UUID, List<Schedule>> activityScheduleMap = new HashMap<>();
        for (Schedule schedule : allSchedules) {
            if (schedule.getLocation() == null) continue;

            Program program = schedule.getProgram();
            if (program == null || program.getUserActivity() == null) continue;

            UserActivity userActivity = program.getUserActivity();
            Activity activity = userActivity.getActivity();
            if (activity == null) continue;

            activityScheduleMap.computeIfAbsent(activity.getId(), k -> new ArrayList<>()).add(schedule);
        }

        // 4. Convert to MapActivityMarkerDto
        List<MapActivityMarkerDto> markers = new ArrayList<>();
        for (Activity activity : allActivities) {
            List<Schedule> schedules = activityScheduleMap.get(activity.getId());
            if (schedules == null || schedules.isEmpty()) continue;

            // Group schedules by location (to count programs at same location)
            Map<String, List<Schedule>> locationGroups = schedules.stream()
                .collect(Collectors.groupingBy(s -> {
                    double lat = Math.round(s.getLocation().getY() * 1000.0) / 1000.0;
                    double lng = Math.round(s.getLocation().getX() * 1000.0) / 1000.0;
                    return lat + "," + lng;
                }));

            // Create one marker per location
            for (Map.Entry<String, List<Schedule>> entry : locationGroups.entrySet()) {
                List<Schedule> locationSchedules = entry.getValue();
                Schedule firstSchedule = locationSchedules.get(0);

                double lat = firstSchedule.getLocation().getY();
                double lng = firstSchedule.getLocation().getX();

                // Pick the schedule with the nearest upcoming starts_at for organizer info
                Instant now = Instant.now();
                Schedule representative = locationSchedules.stream()
                    .filter(s -> s.getStartsAt() != null && s.getStartsAt().isAfter(now))
                    .min(Comparator.comparing(Schedule::getStartsAt))
                    .orElse(firstSchedule);

                Program repProgram = representative.getProgram();
                UserActivity repUa = repProgram != null ? repProgram.getUserActivity() : null;
                User repUser = repUa != null ? repUa.getUser() : null;

                String organizerName = repProgram != null ? repProgram.getOrganizerName() : null;
                String organizerAvatarUrl = repProgram != null ? repProgram.getOrganizerAvatarUrl() : null;
                // Fallback to live user fields if denormalized columns are null
                if (organizerName == null && repUser != null) organizerName = repUser.getDisplayName();
                if (organizerAvatarUrl == null && repUser != null) organizerAvatarUrl = repUser.getAvatarUrl();

                Instant nextSessionAt = representative.getStartsAt() != null
                    && representative.getStartsAt().isAfter(now)
                    ? representative.getStartsAt() : null;

                String address = representative.getPlaceType() == org.program.pair.domain.program.PlaceType.PUBLIC
                    ? representative.getAddressPublic()
                    : Boolean.TRUE.equals(representative.getShowExactAddress())
                        ? representative.getAddressPublic()
                        : representative.getPlaceName();

                // Calculate distance if user location available
                Double distanceKm = null;
                if (userLat != null && userLng != null) {
                    distanceKm = calculateDistance(userLat, userLng, lat, lng);
                }

                markers.add(new MapActivityMarkerDto(
                    activity.getId(),
                    activity.getName(),
                    activity.getSlug(),
                    activity.getCategory() != null ? activity.getCategory().getName() : null,
                    activity.getCategory() != null ? activity.getCategory().getIcon() : null,
                    activity.getCategory() != null ? activity.getCategory().getColorRamp() : null,
                    lat,
                    lng,
                    distanceKm,
                    locationSchedules.size(),
                    repUser != null ? repUser.getId() : null,
                    organizerName,
                    organizerAvatarUrl,
                    nextSessionAt,
                    address
                ));
            }
        }

            // 5. Calculate default center (area with most activities)
            MapActivitiesResponse.DefaultMapCenter defaultCenter = calculateDefaultCenter(markers);

            return new MapActivitiesResponse(markers, defaultCenter);
        } catch (Exception e) {
            // Log error and return empty response with default center
            System.err.println("Error in getAllActivitiesForMap: " + e.getMessage());
            e.printStackTrace();

            // Return empty response with default Paris center
            MapActivitiesResponse.DefaultMapCenter defaultCenter =
                new MapActivitiesResponse.DefaultMapCenter(48.8566, 2.3522, 12);
            return new MapActivitiesResponse(new ArrayList<>(), defaultCenter);
        }
    }

    /**
     * Calculate distance between two points using Haversine formula.
     * @return distance in kilometers
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int EARTH_RADIUS_KM = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return Math.round(EARTH_RADIUS_KM * c * 10.0) / 10.0; // Round to 1 decimal
    }

    /**
     * Calculate default map center by finding the area with most activity density.
     * Uses grid-based clustering to find the densest region.
     */
    private MapActivitiesResponse.DefaultMapCenter calculateDefaultCenter(List<MapActivityMarkerDto> markers) {
        if (markers.isEmpty()) {
            // Default to Paris if no activities
            return new MapActivitiesResponse.DefaultMapCenter(48.8566, 2.3522, 12);
        }

        // Use grid-based clustering to find densest area
        double gridSize = 0.1; // ~10km cells
        Map<String, List<MapActivityMarkerDto>> grid = markers.stream()
            .collect(Collectors.groupingBy(marker -> {
                int gridLat = (int) Math.floor(marker.lat() / gridSize);
                int gridLng = (int) Math.floor(marker.lng() / gridSize);
                return gridLat + "," + gridLng;
            }));

        // Find the grid cell with most activities
        Map.Entry<String, List<MapActivityMarkerDto>> densestCell = grid.entrySet().stream()
            .max(Comparator.comparingInt(e -> e.getValue().size()))
            .orElse(null);

        if (densestCell == null || densestCell.getValue().isEmpty()) {
            // Fallback to average of all markers
            double avgLat = markers.stream().mapToDouble(MapActivityMarkerDto::lat).average().orElse(48.8566);
            double avgLng = markers.stream().mapToDouble(MapActivityMarkerDto::lng).average().orElse(2.3522);
            return new MapActivitiesResponse.DefaultMapCenter(avgLat, avgLng, 12);
        }

        // Calculate center of densest cell
        List<MapActivityMarkerDto> densestMarkers = densestCell.getValue();
        double centerLat = densestMarkers.stream().mapToDouble(MapActivityMarkerDto::lat).average().orElse(0);
        double centerLng = densestMarkers.stream().mapToDouble(MapActivityMarkerDto::lng).average().orElse(0);

        // Adjust zoom based on number of activities in densest area
        int zoom = densestMarkers.size() > 20 ? 13 : densestMarkers.size() > 10 ? 12 : 11;

        return new MapActivitiesResponse.DefaultMapCenter(centerLat, centerLng, zoom);
    }
}
