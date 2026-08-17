package org.program.pair.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.domain.user.dto.*;
import org.program.pair.repository.BadgeAwardRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.InvalidCredentialsException;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final BadgeAwardRepository badgeAwardRepository;
    private final SubscriptionService subscriptionService;
    private final HtmlSanitizer sanitizer;
    private final PasswordEncoder passwordEncoder;
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
            new Coordinate(request.longitude(), request.latitude()));
        user.setLocation(point);
        user.setLastActiveAt(Instant.now());
        userRepository.save(user);
    }

    public void updateAvatar(UUID userId, String avatarUrl) {
        User user = findActiveUser(userId);
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
    }

    public String removeAvatar(UUID userId) {
        User user = findActiveUser(userId);
        String previousAvatarUrl = user.getAvatarUrl();
        user.setAvatarUrl(null);
        userRepository.save(user);
        return previousAvatarUrl;
    }

    public void deactivateAccount(UUID userId) {
        User user = findActiveUser(userId);
        user.setIsActive(false);
        user.setLocationPublic(false);
        userRepository.save(user);
    }

    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = findActiveUser(userId);

        // Verify current password
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Le mot de passe actuel est incorrect.");
        }

        // Validate new password is different from current
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ValidationException("Le nouveau mot de passe doit être différent de l'ancien.");
        }

        // Hash and update password
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Note: Session invalidation would require additional implementation
        // with a token blacklist or token versioning mechanism
    }

    @Transactional(readOnly = true)
    public PrivacySettingsDto getPrivacySettings(UUID userId) {
        User user = findActiveUser(userId);
        PrivacySettings settings = user.getPrivacySettings();
        return new PrivacySettingsDto(
            settings.getProfileVisibility().name(),
            settings.getShowAge(),
            settings.getShowLastActive(),
            settings.getShowLocation(),
            settings.getAllowMessages().name(),
            settings.getShowOnMap(),
            settings.getAllowSubscriptions().name()
        );
    }

    public PrivacySettingsDto updatePrivacySettings(UUID userId, UpdatePrivacySettingsRequest request) {
        User user = findActiveUser(userId);
        PrivacySettings settings = user.getPrivacySettings();

        if (request.profileVisibility() != null) {
            settings.setProfileVisibility(ProfileVisibility.valueOf(request.profileVisibility()));
        }
        if (request.showAge() != null) {
            settings.setShowAge(request.showAge());
        }
        if (request.showLastActive() != null) {
            settings.setShowLastActive(request.showLastActive());
        }
        if (request.showLocation() != null) {
            settings.setShowLocation(request.showLocation());
        }
        if (request.allowMessages() != null) {
            settings.setAllowMessages(MessagePermission.valueOf(request.allowMessages()));
        }
        if (request.showOnMap() != null) {
            settings.setShowOnMap(request.showOnMap());
        }
        if (request.allowSubscriptions() != null) {
            settings.setAllowSubscriptions(
                SubscriptionPermission.valueOf(request.allowSubscriptions()));
        }

        userRepository.save(user);
        return getPrivacySettings(userId);
    }

    @Transactional(readOnly = true)
    public Page<UserPublicDto> searchUsers(
            String query,
            Double latitude,
            Double longitude,
            int page,
            int size,
            UUID requesterId) {

        // Default search radius: 50km
        int radiusMeters = 50000;

        // Calculate offset
        int offset = page * size;

        // Get search results
        List<User> users = userRepository.searchUsers(
            query,
            latitude,
            longitude,
            radiusMeters,
            size,
            offset
        );

        // Get total count for pagination
        long total = userRepository.countSearchResults(
            query,
            latitude,
            longitude,
            radiusMeters
        );

        // Compteurs et état d'abonnement en deux requêtes pour toute la page,
        // et non deux par entrée.
        List<UUID> pageUserIds = users.stream().map(User::getId).toList();
        Map<UUID, Long> subscriberCounts = subscriptionService.countAuthorSubscribers(pageUserIds);
        Set<UUID> subscribedTo = subscriptionService.subscribedAuthorIds(requesterId, pageUserIds);

        List<UserPublicDto> userDtos = users.stream()
            .map(user -> toPublicDto(user,
                subscriberCounts.getOrDefault(user.getId(), 0L),
                subscribedTo.contains(user.getId())))
            .toList();

        return new PageImpl<>(userDtos, PageRequest.of(page, size), total);
    }

    private User findActiveUser(UUID userId) {
        return userRepository.findById(userId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
    }

    /**
     * Profil public d'une personne seule : deux requêtes d'abonnement, le
     * compteur et l'état de l'appelant.
     *
     * <p>La liste paginée passe par la variante à valeurs précalculées : sur une
     * page de résultats, deux requêtes par entrée en feraient deux fois vingt.
     */
    private UserPublicDto toPublicDto(User user, UUID requesterId) {
        return toPublicDto(user,
            subscriptionService.countAuthorSubscribers(user.getId()),
            subscriptionService.isSubscribedToAuthor(requesterId, user.getId()));
    }

    private UserPublicDto toPublicDto(User user, long subscriberCount, boolean subscribed) {
        boolean showOnline = Boolean.TRUE.equals(user.getOnlineStatusVisible())
            && user.getLastActiveAt() != null
            && user.getLastActiveAt().isAfter(Instant.now().minusSeconds(300)); // 5 min

        List<String> badgeCodes = badgeAwardRepository.findByUserId(user.getId()).stream()
            .map(award -> {
                try {
                    return award.getBadge().getCode();
                } catch (IllegalArgumentException e) {
                    // Badge illisible (valeur d'enum inconnue en base) : on l'ignore plutôt
                    // que de faire échouer tout le profil public.
                    log.warn("Badge illisible pour l'award {} de l'utilisateur {} : {}",
                        award.getId(), user.getId(), e.getMessage());
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();

        return new UserPublicDto(
            user.getId(),
            user.getDisplayName(),
            user.getBio(),
            user.getAvatarUrl(),
            user.getVerificationStatus().name(),
            badgeCodes,
            List.of(), // activities — rempli par ActivityService
            showOnline,
            subscriberCount,
            subscribed
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
            List.of(), // activities — rempli par ActivityService
            subscriptionService.countAuthorSubscribers(user.getId())
        );
    }
}
