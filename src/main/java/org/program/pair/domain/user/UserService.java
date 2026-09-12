package org.program.pair.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.trust.BadgeAward;
import org.program.pair.domain.attendance.ReliabilitySignal;
import org.program.pair.domain.guidelines.Guidelines;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.domain.user.dto.*;
import org.program.pair.repository.AfficheRepository;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    /**
     * Pour le seul {@code hasPublishedAffiche} du profil privé.
     *
     * <p>Une dépendance de plus au constructeur, ce que la note ci-dessous sur
     * {@code guidelinesVersion} apprend à éviter : {@code UserServiceTest} monte
     * ce service par {@code @InjectMocks} avec la liste <b>exacte</b> de ses
     * dépendances, et une doublure manquante n'échoue pas là où on l'ajoute —
     * elle fait tomber toute méthode qui rend un profil, y compris celles qui ne
     * parlent que de bio. La doublure est posée dans le même mouvement que ce
     * champ.
     *
     * <p>Le service des affiches n'est volontairement <b>pas</b> injecté à sa
     * place : il porte l'audience, la publication et les gardes de présence,
     * dont ce drapeau n'a que faire. Un booléen sur l'existence d'une ligne se
     * demande au dépôt.
     */
    private final AfficheRepository afficheRepository;
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
     * Les profils publics de <b>plusieurs</b> personnes, pour le même lecteur.
     *
     * <p>Le jumeau groupé de {@link #getPublicProfile}. Celui-ci coûte trois
     * requêtes par personne — nombre d'abonnés, suis-je abonné, badges — et
     * cinq surfaces internes l'appellent en boucle. Sur les cartes-souvenirs
     * d'un compte réel, c'était 105 requêtes pour trois hôtes distincts.
     *
     * <p><b>La règle de visibilité n'est pas recopiée</b> : les deux chemins
     * finissent dans la même fabrique privée, qui décide seule de ce qu'un
     * profil montre. Ce qui change ici est uniquement la façon dont ses trois
     * entrées sont rassemblées. Une seconde définition aurait servi des profils
     * plus bavards sur une page que sur une autre, sans qu'aucune erreur ne le
     * dise.
     *
     * <p><b>Rend moins d'entrées qu'on ne lui en demande</b> quand une personne
     * est inconnue ou désactivée : là où la variante unitaire lève, celle-ci
     * omet, et laisse l'appelant décider. Aucun appelant n'a le droit de traiter
     * une absence comme un profil vide.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UserPublicDto> getPublicProfiles(Collection<UUID> userIds, UUID requesterId) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = new LinkedHashSet<>(userIds);

        List<User> users = userRepository.findAllById(ids).stream()
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .toList();
        if (users.isEmpty()) {
            return Map.of();
        }
        Set<UUID> actifs = users.stream().map(User::getId).collect(Collectors.toSet());

        Map<UUID, Long> abonnes = subscriptionService.countAuthorSubscribers(actifs);
        Set<UUID> suivis = subscriptionService.subscribedAuthorIds(requesterId, actifs);
        Map<UUID, List<BadgeAward>> badges = badgeAwardRepository.findByUserIdsWithBadge(actifs)
            .stream()
            .collect(Collectors.groupingBy(award -> award.getUser().getId()));

        Map<UUID, UserPublicDto> profils = new LinkedHashMap<>();
        for (User user : users) {
            profils.put(user.getId(), toPublicDto(
                user,
                abonnes.getOrDefault(user.getId(), 0L),
                suivis.contains(user.getId()),
                badges.getOrDefault(user.getId(), List.of())));
        }
        return profils;
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

    /**
     * Retire le compte de la circulation — et <b>rien d'autre</b>.
     *
     * <p><b>Pourquoi ce n'est pas {@code findActiveUser}.</b> Toutes les autres
     * méthodes de ce service refusent un compte déjà inactif, et c'est juste :
     * on ne modifie pas le profil de quelqu'un qui n'est plus là. Celle-ci est
     * le seul cas où le refus est faux. Elle est appelée par les deux routes de
     * suppression, que l'application déclenche sur un geste unique et rejoue
     * après une coupure réseau : le second appel tombait alors sur le {@code 404}
     * de {@code findActiveUser}, que l'app affiche comme un échec de la
     * suppression — alors qu'elle avait réussi. L'état visé est atteint, on rend
     * donc le même succès. Un compte <i>inconnu</i> reste, lui, un {@code 404} :
     * il n'y a rien à désactiver.
     *
     * <p><b>Ce que cette méthode ne fait délibérément pas.</b> Annuler les
     * créneaux animés, désinscrire des créneaux d'autrui, prévenir les inscrits,
     * révoquer les jetons de session : tout cela est attendu et arrive dans un
     * lot dédié. Les poser ici les mettrait dans <i>cette</i> transaction, où le
     * moindre échec annulerait le {@code is_active = false} — et la demande de
     * suppression serait à nouveau perdue, pour une raison de plus.
     *
     * <p>La date de la demande n'est pas écrite sur le compte : la colonne
     * n'existe pas encore. C'est la ligne d'audit {@code GDPR_DELETE_REQUEST}
     * posée par les contrôleurs qui la porte, et elle suffit à faire courir le
     * délai de trente jours.
     */
    public void deactivateAccount(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            return;
        }
        user.setIsActive(false);
        // Le compte disparaît de la carte dans le même mouvement : laisser le
        // point public survivre à la désactivation serait le contraire de ce
        // qu'on vient de demander.
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
        return toPublicDto(user, subscriberCount, subscribed, null);
    }

    /**
     * @param awardsDejaCharges les badges de cette personne quand un appelant les
     *                          a déjà rapatriés pour tout un lot ; {@code null}
     *                          quand il faut les lire ici. C'est le SEUL écart
     *                          entre le rendu unitaire et le rendu groupé — la
     *                          décision de visibilité, elle, reste écrite une
     *                          fois, plus bas.
     */
    private UserPublicDto toPublicDto(User user, long subscriberCount, boolean subscribed,
                                      List<BadgeAward> awardsDejaCharges) {
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
            ? (awardsDejaCharges != null
                    ? awardsDejaCharges
                    : badgeAwardRepository.findByUserIdWithBadge(user.getId())).stream()
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
            Guidelines.acceptanceRequired(guidelinesVersion, user.getGuidelinesVersion()),
            user.getVerificationEmailDelivery().name(),
            // Toutes audiences confondues, NOBODY compris : la question est « ai-je
            // fait ce geste ? ». Une requête, comme le compteur d'abonnés
            // au-dessus, et sur une réponse que le client charge déjà au
            // démarrage — c'est tout l'objet du champ.
            afficheRepository.existsByUserId(user.getId())
        );
    }
}
