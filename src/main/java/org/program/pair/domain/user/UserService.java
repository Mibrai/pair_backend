package org.program.pair.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.attendance.ReliabilitySignal;
import org.program.pair.domain.guidelines.Guidelines;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.domain.user.dto.*;
import org.program.pair.repository.BadgeAwardRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.InvalidCredentialsException;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Version en vigueur des règles, injectée par champ et non par le
     * constructeur : ce service est monté dans ses tests unitaires par
     * {@code @InjectMocks} avec la liste exacte de ses dépendances, et lui en
     * ajouter une casserait une classe de test étrangère au sujet.
     */
    @Value("${pair.guidelines.current-version:1.0}")
    private String guidelinesVersion;

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

    /**
     * Mon profil tel qu'un inconnu le reçoit.
     *
     * <p><b>Le même code, pas un code équivalent.</b> C'est toute la valeur du
     * lot : un aperçu qui divergerait du profil réel serait pire que pas
     * d'aperçu du tout — il donnerait confiance dans une réponse fausse. Cette
     * méthode ne recompose rien ; elle appelle {@code toPublicDto} avec la
     * relation d'un tiers sans lien.
     *
     * <p>Sans lien, précisément : {@code subscribed} vaut faux, ce qui est la
     * situation la plus restrictive et donc celle qu'il faut montrer. Quelqu'un
     * qui règle son profil sur « abonnés seulement » doit voir ce que voit un
     * inconnu, pas ce que voit son abonné.
     */
    @Transactional(readOnly = true)
    public UserPublicDto getMyProfilePreview(UUID userId) {
        User me = findActiveUser(userId);
        return toPublicDto(me, subscriptionService.countAuthorSubscribers(userId), false);
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
            offset,
            requesterId
        );

        // Get total count for pagination
        long total = userRepository.countSearchResults(
            query,
            latitude,
            longitude,
            radiusMeters,
            requesterId
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

    /**
     * Le profil public, filtré par les réglages de confidentialité de la personne.
     *
     * <p><b>Ces réglages étaient morts.</b> {@code profileVisibility} était
     * stocké, réglable par une route dédiée, relu par une autre — et lu par
     * aucun code de rendu : un profil réglé « privé » était servi intégralement
     * à quiconque. Idem pour {@code showLastActive}. Le lot D4 les applique,
     * parce qu'un aperçu de profil qui n'a rien à filtrer ne prouve rien.
     *
     * <p><b>Ce qui reste toujours visible :</b> nom affiché, avatar, badge de
     * vérification. Ce sont les éléments par lesquels une personne est
     * identifiée dans une conversation ou sur la liste des participants d'un
     * créneau, et cinq surfaces internes construisent ce DTO pour cela. Les
     * masquer ne protégerait personne : ça casserait l'application.
     *
     * <p><b>Ce qui se masque :</b> la biographie, les badges, la présence en
     * ligne, le nombre d'abonnés et le signal de fiabilité. Autrement dit ce qui
     * relève de la fiche, pas de l'identification.
     *
     * <p><b>Sur {@code FRIENDS} :</b> meetDo n'a pas de notion d'amitié. Le seul
     * lien explicite entre deux personnes est l'abonnement, et c'est donc lui
     * qui fait foi. Il est en outre déjà calculé pour ce DTO, si bien que le
     * filtre ne coûte aucune requête supplémentaire — ce qui compte, puisque ce
     * mapping est appelé une fois par participant sur certaines pages.
     */
    private UserPublicDto toPublicDto(User user, long subscriberCount, boolean subscribed) {
        PrivacySettings privacy = user.getPrivacySettings() != null
            ? user.getPrivacySettings()
            : new PrivacySettings();

        ProfileVisibility visibility = privacy.getProfileVisibility() != null
            ? privacy.getProfileVisibility()
            : ProfileVisibility.PUBLIC;

        boolean detailsVisible = visibility == ProfileVisibility.PUBLIC
            || (visibility == ProfileVisibility.FRIENDS && subscribed);

        // Deux réglages disent la même chose : le champ historique
        // onlineStatusVisible et showLastActive. On exige les deux — c'est le
        // seul choix qui ne montre jamais plus qu'avant, et il faudra un jour
        // n'en garder qu'un.
        boolean showOnline = detailsVisible
            && Boolean.TRUE.equals(user.getOnlineStatusVisible())
            && Boolean.TRUE.equals(privacy.getShowLastActive())
            && user.getLastActiveAt() != null
            && user.getLastActiveAt().isAfter(Instant.now().minusSeconds(300)); // 5 min

        // Un profil dont les détails sont masqués rend une liste de badges vide :
        // aller les chercher en base serait du travail jeté. Le chargement est
        // donc conditionné, et il passe par le dépôt qui rapatrie le badge dans
        // la même requête — sinon chaque code lu ci-dessous en coûterait une.
        List<String> badgeCodes = detailsVisible
            ? badgeAwardRepository.findByUserIdWithBadge(user.getId()).stream()
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
                .toList()
            : List.<String>of();

        return new UserPublicDto(
            user.getId(),
            user.getDisplayName(),
            detailsVisible ? user.getBio() : null,
            user.getAvatarUrl(),
            user.getVerificationStatus().name(),
            badgeCodes,
            List.of(), // activities — rempli par ActivityService
            showOnline,
            detailsVisible ? subscriberCount : null,
            subscribed,
            detailsVisible
                ? ReliabilitySignal.of(user.getJoinedSlotsCount(), user.getAttendanceCount())
                : null);
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
            subscriptionService.countAuthorSubscribers(user.getId()),
            user.getOnboardingCompletedAt(),
            user.getOnboardingStep() == null ? null : user.getOnboardingStep().name(),
            user.getGuidelinesVersion(),
            Guidelines.acceptanceRequired(guidelinesVersion, user.getGuidelinesVersion())
        );
    }
}
