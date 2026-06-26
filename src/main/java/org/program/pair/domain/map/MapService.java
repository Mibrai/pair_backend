package org.program.pair.domain.map;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.map.dto.MapActivityBadgeDto;
import org.program.pair.domain.map.dto.MapSearchRequest;
import org.program.pair.domain.map.dto.MapUserDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MapService {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
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
}
