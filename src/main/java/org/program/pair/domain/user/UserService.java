package org.program.pair.domain.user;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.user.dto.*;
import org.program.pair.repository.BadgeAwardRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final BadgeAwardRepository badgeAwardRepository;
    private final HtmlSanitizer sanitizer;
    private final GeometryFactory geometryFactory = new GeometryFactory(
        new PrecisionModel(), 4326);

    @Transactional(readOnly = true)
    public UserPrivateDto getMyProfile(UUID userId) {
        User user = findActiveUser(userId);
        return toPrivateDto(user);
    }

    @Transactional(readOnly = true)
    public UserPublicDto getPublicProfile(UUID targetId, UUID requesterId) {
        User target = findActiveUser(targetId);
        return toPublicDto(target, requesterId);
    }

    public UserPrivateDto updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findActiveUser(userId);

        if (request.displayName() != null) {
            user.setDisplayName(sanitizer.sanitize(request.displayName()).strip());
        }
        if (request.bio() != null) {
            user.setBio(sanitizer.sanitize(request.bio()));
        }
        if (request.locationPublic() != null) {
            user.setLocationPublic(request.locationPublic());
        }
        if (request.onlineStatusVisible() != null) {
            user.setOnlineStatusVisible(request.onlineStatusVisible());
        }
        if (request.receiveMessages() != null) {
            user.setReceiveMessages(request.receiveMessages());
        }
        if (request.blurRadiusM() != null) {
            // Minimum 100m — on n'accepte pas de floutage inférieur
            user.setBlurRadiusM(Math.max(100, request.blurRadiusM()));
        }

        return toPrivateDto(userRepository.save(user));
    }

    public void updateLocation(UUID userId, UpdateLocationRequest request) {
        User user = findActiveUser(userId);
        Point point = geometryFactory.createPoint(
            new Coordinate(request.lng(), request.lat()));
        user.setLocation(point);
        user.setLastActiveAt(Instant.now());
        userRepository.save(user);
    }

    public void updateAvatar(UUID userId, String avatarUrl) {
        User user = findActiveUser(userId);
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
    }

    public void deactivateAccount(UUID userId) {
        User user = findActiveUser(userId);
        user.setIsActive(false);
        user.setLocationPublic(false);
        userRepository.save(user);
    }

    private User findActiveUser(UUID userId) {
        return userRepository.findById(userId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
    }

    private UserPublicDto toPublicDto(User user, UUID requesterId) {
        boolean showOnline = Boolean.TRUE.equals(user.getOnlineStatusVisible())
            && user.getLastActiveAt() != null
            && user.getLastActiveAt().isAfter(Instant.now().minusSeconds(300)); // 5 min

        List<String> badgeCodes = badgeAwardRepository.findByUserId(user.getId()).stream()
            .map(award -> award.getBadge().getCode())
            .toList();

        return new UserPublicDto(
            user.getId(),
            user.getDisplayName(),
            user.getBio(),
            user.getAvatarUrl(),
            user.getVerificationStatus().name(),
            badgeCodes,
            List.of(), // activities — rempli par ActivityService
            showOnline
        );
    }

    private UserPrivateDto toPrivateDto(User user) {
        Double lat = null;
        Double lng = null;

        if (user.getLocation() != null) {
            lat = user.getLocation().getY();
            lng = user.getLocation().getX();
        }

        return new UserPrivateDto(
            user.getId(),
            user.getEmail(),
            user.getPhone(),
            user.getDisplayName(),
            user.getBio(),
            user.getAvatarUrl(),
            lat,
            lng,
            user.getBlurRadiusM(),
            user.getLocationPublic(),
            user.getOnlineStatusVisible(),
            user.getReceiveMessages(),
            user.getVerificationStatus().name(),
            user.getCreatedAt(),
            List.of() // activities — rempli par ActivityService
        );
    }
}
