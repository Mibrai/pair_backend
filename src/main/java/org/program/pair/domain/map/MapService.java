package org.program.pair.domain.map;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.map.dto.*;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MapService {

    private final org.program.pair.repository.UserLanguageRepository userLanguageRepository;
    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final ActivityRepository activityRepository;
    private final ScheduleRepository scheduleRepository;
    private final ProgramRepository programRepository;
    private final UserProgramRepository userProgramRepository;
    private final org.program.pair.domain.block.BlockFilterService blockFilterService;
    private final Random random = new Random();

    /**
     * Plafond de personnes examinées pour agréger la couche « activités » de
     * {@code /map/bounds}. Une {@code Activity} n'ayant pas de coordonnées, elle
     * n'existe sur la carte qu'à travers ceux qui la déclarent ; au-delà de ce
     * plafond, la couche est calculée sur un échantillon et {@code truncated}
     * passe à vrai.
     */
    private static final int MAX_USERS_FOR_ACTIVITY_AGGREGATION = 1_000;

    public List<MapUserDto> getUsersOnMap(MapSearchRequest request, UUID requesterId) {
        // 1. Find visible users in radius
        List<User> nearbyUsers = userRepository.findVisibleUsersInRadius(
            request.lat(), request.lng(), request.radiusMeters(), 100, 0, requesterId);

        // Filtre de langue, appliqué sur la page déjà rapportée — comme celui
        // d'activité juste en dessous. La limite de 100 est posée en base avant
        // les deux, si bien qu'un filtre sélectif rend moins de marqueurs qu'il
        // n'en existe. C'est la dette que porte déjà cette route ; la creuser
        // n'aurait pas été mieux que la signaler.
        java.util.Set<String> languages = request.effectiveLanguages();
        if (!languages.isEmpty() && !nearbyUsers.isEmpty()) {
            java.util.Set<UUID> speakers = new java.util.HashSet<>(
                userLanguageRepository.findUserIdsSpeaking(
                    nearbyUsers.stream().map(User::getId).toList(), languages));
            nearbyUsers = nearbyUsers.stream()
                .filter(u -> speakers.contains(u.getId()))
                .toList();
        }

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
            centerLat, centerLng, radiusMeters, 1000, 0, requesterId);

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

                List<double[]> points = cellUsers.stream()
                    .map(u -> new double[]{u.getLocation().getY(), u.getLocation().getX()})
                    .toList();

                // Determine cluster type based on size
                String type = cellUsers.size() == 1 ? "single" : "cluster";

                // Les bornes portent l'étendue des membres : sans elles, un tap sur
                // un cluster ne peut pas recadrer la carte dessus.
                return MapCluster.of(points, type, null);
            })
            .toList();
    }

    /**
     * Ancre d'échelle : au palier {@value #GRID_ANCHOR_ZOOM}, une cellule vaut
     * {@value #GRID_ANCHOR_SIZE}° (~11 km). La pente seule ne dit pas où passer,
     * seulement comment descendre ; cette valeur fixe le reste.
     */
    private static final int GRID_ANCHOR_ZOOM = 12;
    private static final double GRID_ANCHOR_SIZE = 0.1;
    /** Bornes du zoom, validées par les deux routes qui agrègent. */
    private static final int MIN_ZOOM = 1;
    private static final int MAX_ZOOM = 20;

    /**
     * Côté d'une cellule de grille, en degrés, pour un palier de zoom donné.
     *
     * <p><b>La maille est divisée par deux à chaque palier</b> — la seule pente
     * correcte, puisque c'est celle de la résolution de la carte elle-même. Une
     * cellule occupe donc toujours la même fraction de l'écran, ce qui est la
     * seule propriété que l'agrégation doive tenir : regrouper ce qui se
     * chevauche à l'affichage, et rien d'autre.
     *
     * <p>La table qui occupait cette place ne divisait la maille par deux que
     * <i>tous les deux paliers</i>, si bien que la cellule doublait de taille
     * apparente à chaque niveau gagné. Ce n'était pas un réglage trop grossier
     * en un point mais une pente fausse sur toute la plage, et elle se trompait
     * dans les deux sens à la fois. Mesuré en projection Web Mercator à la
     * latitude de Paris, le côté d'une cellule passait de <b>11 px au palier
     * 1</b> à <b>11 332 px au palier 20</b> :
     *
     * <ul>
     *   <li>au zoom maximal, l'agrégation regroupait des marqueurs distants de
     *       vingt-huit largeurs d'écran, que l'utilisateur ne pouvait de toute
     *       façon jamais voir ensemble — d'où une pastille irrésolvable, que
     *       zoomer ne défaisait pas ;</li>
     *   <li>sur une carte monde, à l'inverse, elle ne regroupait presque rien :
     *       une cellule de 11 px laissait des dizaines de pastilles se
     *       superposer là où l'agrégation est le plus nécessaire.</li>
     * </ul>
     *
     * <p>Corollaire assumé du bas de la plage : au palier 1 la cellule vaut
     * 204,8°, soit davantage que l'étendue des latitudes — celles-ci cessent
     * donc de fragmenter quoi que ce soit, et il ne subsiste que les frontières
     * décrites en fin de javadoc. Une carte monde rend alors quelques disques
     * portant de gros totaux, ce qui est ce qu'on veut y lire.
     *
     * <p>La maille s'applique en degrés aux deux axes, sans correction en
     * {@code cos(lat)} : une cellule est donc un rectangle qui s'aplatit vers le
     * nord (1 113 m × 733 m à Paris pour 0,01°). Corriger cela demande de
     * projeter en Mercator plutôt que d'ajuster une taille, et n'est pas
     * nécessaire pour que la maille suive le zoom.
     *
     * <p>Enfin, il s'agit d'une <b>grille fixe, pas d'un regroupement par
     * proximité</b> : deux marqueurs distants de dix mètres de part et d'autre
     * d'une frontière de cellule ne sont pas groupés. Élargir la maille ne crée
     * pas ces frontières, mais rend chacune plus lourde de conséquences — aux
     * paliers les plus bas, les seules qui subsistent passent par l'équateur et
     * le méridien de Greenwich, de sorte qu'une zone à cheval sur l'un d'eux
     * rend deux groupes au lieu d'un. Le client doit continuer de traiter les
     * bornes d'un cluster comme l'étendue de ses membres, jamais comme le
     * voisinage complet de son centre.
     */
    double calculateGridSize(int zoom) {
        // Le zoom est validé dans [1, 20] par les deux routes ; le bornage n'est
        // là que pour qu'un appel non validé — un futur appelant, un test — ne
        // puisse pas produire une maille aberrante.
        int clamped = Math.min(Math.max(zoom, MIN_ZOOM), MAX_ZOOM);
        // Mise à l'échelle par une puissance de deux : exacte en virgule
        // flottante, donc gridSize(z) vaut très exactement gridSize(z-1) / 2.
        return GRID_ANCHOR_SIZE * Math.pow(2, GRID_ANCHOR_ZOOM - clamped);
    }

    /** Pas de la quatrième décimale, sur laquelle les positions sont arrondies (~11 m). */
    private static final double COORDINATE_GRID = 0.0001;

    /**
     * Déplacement aléatoire dans un disque de rayon {@code blur_radius_m},
     * <b>qui ne retombe jamais sur la position exacte</b>.
     *
     * <p>Cette dernière garantie manquait, et elle n'est pas cosmétique : le
     * tirage étant continu puis arrondi à la quatrième décimale, la position
     * affichée valait la position réelle environ <b>6 % du temps</b> pour un
     * rayon de 500 m. Une position exacte rendue une fois sur seize n'est pas
     * un flou — et c'est la promesse que le produit fait à l'utilisateur qui
     * règle son rayon.
     *
     * <p>C'était aussi ce qui rendait
     * {@code MapVisibilityIntegrationTest.positionAffichee_doitEtreFlouttee_pasExacte}
     * intermittent : le test disait vrai, l'implémentation ne tenait pas ce
     * qu'il vérifiait.
     *
     * <p>Quand l'arrondi ramène sur la position d'origine, le point est poussé
     * d'un pas de grille (~11 m) <b>dans le sens du tirage</b> — jamais vers la
     * position réelle. Le point peut alors dépasser le rayon demandé de ce pas,
     * ce qui est sans conséquence : s'éloigner ne dévoile rien.
     */
    double[] applyBlur(double lat, double lng, int radiusMeters) {
        double radiusDeg = radiusMeters / 111320.0;
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = random.nextDouble() * radiusDeg;
        double blurredLat = lat + distance * Math.cos(angle);
        double blurredLng = lng + distance * Math.sin(angle)
            / Math.cos(Math.toRadians(lat));
        return new double[]{
            displacedFrom(lat, blurredLat),
            displacedFrom(lng, blurredLng)
        };
    }

    /**
     * Arrondi à la quatrième décimale, en garantissant de ne pas rendre la
     * coordonnée d'origine.
     *
     * <p>Le cas ne peut se produire que si l'origine est elle-même sur la
     * grille : une coordonnée plus précise ne peut pas être égale à un arrondi.
     * La poussée est donc rare, et n'intervient jamais quand elle serait
     * inutile.
     */
    private static double displacedFrom(double original, double blurred) {
        double rounded = round4(blurred);
        if (rounded != original) {
            return rounded;
        }
        double away = blurred >= original ? COORDINATE_GRID : -COORDINATE_GRID;
        return round4(rounded + away);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public List<MapUserDto> getUsersInBounds(MapBoundsRequest request, UUID requesterId) {
        return usersLayer(request, requesterId).items();
    }

    private Layer<MapUserDto> usersLayer(MapBoundsRequest request, UUID requesterId) {
        // Le filtre bbox est en SQL : le « rayon déduit de la bbox puis filtre
        // en Java » d'avant ne couvrait pas les coins du rectangle et plafonnait
        // à 100 km, écartant silencieusement des personnes pourtant dans les
        // bornes demandées.
        List<User> nearbyUsers = userRepository.findVisibleUsersInBounds(
            request.south(), request.north(), request.west(), request.east(),
            request.limit(), request.offset(), requesterId);
        int fetched = nearbyUsers.size();

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
        List<MapUserDto> items = nearbyUsers.stream()
            .filter(u -> !u.getId().equals(requesterId))
            .map(u -> toMapDto(u, null))
            .filter(dto -> dto != null)
            .toList();

        // La troncature se juge sur ce que la base a écarté, pas sur la taille
        // de la liste finale : l'exclusion de l'appelant et le filtre de niveau
        // s'appliquent après le limit et la réduiraient sans qu'aucun marqueur
        // n'ait manqué.
        long total = userRepository.countVisibleUsersInBounds(
            request.south(), request.north(), request.west(), request.east(), requesterId);
        boolean truncated = total > (long) request.offset() + fetched;

        return new Layer<>(items, (int) Math.min(total, Integer.MAX_VALUE), truncated);
    }

    public List<MapActivityDto> getActivitiesInBounds(MapBoundsRequest request, UUID requesterId) {
        return activitiesLayer(request, requesterId).items();
    }

    private Layer<MapActivityDto> activitiesLayer(MapBoundsRequest request, UUID requesterId) {
        // Une Activity n'a pas de coordonnées : la couche est agrégée depuis les
        // personnes qui la déclarent. D'où un plafond interne sur ces personnes,
        // qui est aussi la raison pour laquelle totalInBounds n'est qu'un
        // minorant sur cette couche (cf. MapMarkersResponse).
        List<User> nearbyUsers = userRepository.findVisibleUsersInBounds(
            request.south(), request.north(), request.west(), request.east(),
            MAX_USERS_FOR_ACTIVITY_AGGREGATION, 0, requesterId);

        // Get unique activities from these users
        Map<UUID, List<User>> activityUserMap = new HashMap<>();
        for (User user : nearbyUsers) {
            List<UserActivity> userActivities = userActivityRepository.findVisibleByUserId(user.getId());
            for (UserActivity ua : userActivities) {
                activityUserMap.computeIfAbsent(ua.getActivity().getId(), k -> new ArrayList<>()).add(user);
            }
        }

        // Convert to DTOs with representative location (first user's location)
        List<MapActivityDto> aggregated = activityUserMap.entrySet().stream()
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
            .sorted(Comparator.comparing(dto -> dto.id().toString()))
            .toList();

        long usersInBounds = userRepository.countVisibleUsersInBounds(
            request.south(), request.north(), request.west(), request.east(), requesterId);
        boolean sampled = usersInBounds > MAX_USERS_FOR_ACTIVITY_AGGREGATION;

        List<MapActivityDto> items = aggregated.stream().limit(request.limit()).toList();
        return new Layer<>(items, aggregated.size(), sampled || aggregated.size() > items.size());
    }

    public List<MapProgramDto> getProgramsInBounds(MapBoundsRequest request) {
        return programsLayer(request).items();
    }

    private Layer<MapProgramDto> programsLayer(MapBoundsRequest request) {
        // Le scan complet de la table des créneaux, suivi d'un filtre en Java,
        // a disparu : la bbox est appliquée en base, comme sur /map/activities.
        // Le filtre de catégorie l'a rejointe — il ne servait à rien de charger des
        // créneaux pour les jeter juste après.
        boolean filterByCategory = request.categoryIds() != null && !request.categoryIds().isEmpty();
        List<UUID> ids = scheduleRepository.findLocatedScheduleIdsWithin(
            null, null, null,
            request.north(), request.south(), request.east(), request.west(),
            filterByCategory,
            filterByCategory ? request.categoryIds() : ScheduleRepository.NO_CATEGORY_FILTER);
        List<Schedule> schedulesInBounds = ids.isEmpty()
            ? List.of()
            : scheduleRepository.findWithActivityDetailsByIds(ids);

        // Convert to DTOs
        List<MapProgramDto> all = schedulesInBounds.stream()
            .sorted(Comparator.comparing(s -> s.getId().toString()))
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
            .toList();

        // skip/limit sur une liste triée : sans l'ordre, deux appels identiques
        // pouvaient rendre des pages qui se recouvrent ou se manquent.
        List<MapProgramDto> items = all.stream()
            .skip(request.offset())
            .limit(request.limit())
            .toList();

        return new Layer<>(items, all.size(), all.size() > (long) request.offset() + items.size());
    }

    public List<?> getNearbyItems(String type, double lat, double lng, int radiusKm, UUID requesterId) {
        int radiusMeters = radiusKm * 1000;

        return switch (type.toLowerCase()) {
            case "users" -> {
                List<User> users = userRepository.findVisibleUsersInRadius(
                    lat, lng, radiusMeters, 100, 0, requesterId);
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
        validateBounds(request);

        Layer<MapUserDto> users = usersLayer(request, requesterId);
        Layer<MapActivityDto> activities = activitiesLayer(request, requesterId);
        Layer<MapProgramDto> programs = programsLayer(request);

        return new MapMarkersResponse(
            users.items(), activities.items(), programs.items(),
            users.truncated() || activities.truncated() || programs.truncated(),
            users.totalInBounds() + activities.totalInBounds() + programs.totalInBounds());
    }

    /** Une couche de marqueurs, avec de quoi dire si elle est complète. */
    private record Layer<T>(List<T> items, int totalInBounds, boolean truncated) {}

    /**
     * Les quatre bornes sont déjà obligatoires ({@code MapBoundsRequest}) ; il
     * reste à refuser celles qui ne décrivent pas un rectangle. Mêmes règles et
     * même code que sur {@code /map/activities}, pour que le client n'ait pas
     * deux comportements à apprendre.
     */
    private void validateBounds(MapBoundsRequest request) {
        if (request.south() > request.north()) {
            throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                "'south' ne peut pas être supérieur à 'north'.");
        }
        if (request.west() > request.east()) {
            throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                "'west' ne peut pas être supérieur à 'east' : une zone à cheval "
                    + "sur l'antiméridien n'est pas supportée.");
        }
        if (request.south() < -90 || request.north() > 90
                || request.west() < -180 || request.east() > 180) {
            throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                "Les bornes doivent rester dans [-90, 90] en latitude et "
                    + "[-180, 180] en longitude.");
        }
        if (request.limit() < 1) {
            throw new ValidationException(ErrorCode.MAP_LIMIT_OUT_OF_RANGE,
                "Le paramètre 'limit' doit être supérieur ou égal à 1.");
        }
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

    // Bornes du rayon, documentées dans l'OpenAPI via MapActivitiesRequest.
    private static final int MIN_RADIUS_METERS = 1;
    private static final int MAX_RADIUS_METERS = 200_000;
    // Plafond dur du nombre de marqueurs : un limit supérieur est ramené ici.
    private static final int MAX_MARKER_LIMIT = 1_000;

    /**
     * Marqueurs d'activité de la carte, éventuellement bornés par un rayon et/ou
     * une bbox.
     *
     * <p>Sans aucun paramètre de bornage, aucun filtre géographique n'est
     * appliqué et aucune limite ne l'est non plus.
     *
     * <p><b>Une seule population, filtrée par défaut</b> : seules les activités
     * ayant une séance à venir sont renvoyées, marqueurs isolés comme membres
     * des clusters (voir {@link #keepUpcoming}). C'est un changement de
     * comportement observable, assumé, dont {@code includeExpired=true} est la
     * sortie de secours.
     *
     * <p>Avec bornage, le filtre géographique est appliqué <b>en SQL</b>
     * (PostGIS), pas après coup sur une liste déjà chargée : c'était le point
     * de la demande. Le tri est déterministe — distance croissante quand la
     * position de l'utilisateur est connue, puis activityId, puis coordonnées —
     * pour que la troncature garde les marqueurs les plus proches et que deux
     * appels identiques renvoient la même chose.
     *
     * <p><b>Cette méthode laisse sortir ses erreurs, et il ne faut pas le
     * défaire.</b> Elle a porté cinq semaines un {@code catch (Exception)} qui
     * renvoyait une carte vide en {@code 200} — posé le 2026-07-04 comme
     * échafaudage de débogage pour une {@code LazyInitializationException}, que
     * le commit suivant a corrigée à la source par un {@code JOIN FETCH}. La
     * cause était morte, le masque est resté.
     *
     * <p>Ce qu'il coûtait : une panne de la route la plus visible de l'app
     * devenait indiscernable d'une zone sans activité — côté serveur comme côté
     * client, qui n'avait plus même le statut HTTP pour trancher. C'est la forme
     * exacte de l'incident média du 2026-08-11, où un défaut d'infrastructure
     * s'est présenté trois semaines durant comme un contenu absent.
     *
     * <p>Une défaillance produit donc un {@code 500 INTERNAL_ERROR}, journalisé
     * par {@code GlobalExceptionHandler} avec le {@code rid:} du MDC — donc
     * corrélable avec le {@code X-Request-Id} que le client consigne. Il n'y a
     * volontairement aucun {@code log} ici : il ferait doublon.
     *
     * <p>Une zone réellement vide reste, elle, un {@code 200} avec une liste
     * vide. C'est le chemin normal, et c'est toute la frontière : vide n'est pas
     * en panne.
     *
     * @return marqueurs, centre par défaut, et de quoi savoir si la carte est
     *         partielle ({@code truncated}, {@code totalInBounds})
     */
    public MapActivitiesResponse getAllActivitiesForMap(MapActivitiesRequest request, UUID viewerId) {
        validate(request);

        // 1. Créneaux localisés, bornés en base si un filtre est demandé.
        List<Schedule> allSchedules = loadSchedules(request);

        // 2. Les personnes bloquées, dans un sens ou dans l'autre.
        //
        // Filtré en mémoire, contrairement au reste du lot A3 qui pousse le
        // prédicat dans le SQL. Ce n'est pas une facilité : un marqueur agrège
        // ici plusieurs organisateurs sur une même activité, et les compteurs de
        // la réponse — totalInBounds, les count de clusters, truncated — sont
        // tous dérivés de la liste après agrégation. Écarter les créneaux avant
        // de construire les marqueurs les laisse donc exacts, là où un filtrage
        // en SQL sur les créneaux seuls n'aurait rien changé au problème et un
        // post-filtrage des marqueurs les aurait tous faussés.
        Set<UUID> invisible = blockFilterService.invisibleTo(viewerId);

        // 3. Build a map of activity -> list of schedule locations
        Map<UUID, List<Schedule>> activityScheduleMap = new LinkedHashMap<>();
        for (Schedule schedule : allSchedules) {
            if (schedule.getLocation() == null) continue;

            Program program = schedule.getProgram();
            if (program == null || program.getUserActivity() == null) continue;

            UserActivity userActivity = program.getUserActivity();
            Activity activity = userActivity.getActivity();
            if (activity == null) continue;

            // Un profil bloqué qui garde ses activités visibles sur la carte est
            // un profil qui n'est pas bloqué.
            if (!invisible.isEmpty() && userActivity.getUser() != null
                    && invisible.contains(userActivity.getUser().getId())) {
                continue;
            }

            // ATTENTION : la clé est l'activité du RÉFÉRENTIEL, sans l'organisateur.
            //
            // Deux personnes proposant la même activité au même lieu (à 111 m près,
            // cf. l'arrondi ci-dessous) se fondent donc en un seul marqueur, et
            // l'organizerId rendu est celui d'un créneau « représentatif » choisi
            // par sa date — pas forcément celui que l'utilisateur croit voir.
            // S'abonner à « l'auteur » depuis ce marqueur peut donc abonner à
            // quelqu'un d'autre.
            //
            // Connu et assumé au 2026-08-17 : le client Flutter n'alimente plus
            // aucun rendu depuis /map/activities (ses puces d'abonnement lisent un
            // ProgramDto via le pin de programme), donc le défaut n'atteint
            // personne. Il n'a pas disparu pour autant. Tout nouveau consommateur
            // de cette route en hérite : le corriger suppose de faire entrer
            // userActivityId dans la clé, ce qui augmente la cardinalité des
            // marqueurs et doit être annoncé aux clients avant, pas après.
            // Voir docs/specs/REPONSE_BACKEND_ABONNEMENTS_2026-08.md §1.3.
            activityScheduleMap.computeIfAbsent(activity.getId(), k -> new ArrayList<>()).add(schedule);
        }

        Double userLat = request.userLat();
        Double userLng = request.userLng();

        // 4. Convert to MapActivityMarkerDto
        // On itère les activités effectivement porteuses de créneaux localisés,
        // au lieu de charger tout le référentiel : les autres étaient de toute
        // façon écartées, et activityRepository.findAll() était un scan complet
        // à chaque ouverture de carte.
        List<MapActivityMarkerDto> markers = new ArrayList<>();
        for (List<Schedule> schedules : activityScheduleMap.values()) {
            Activity activity = schedules.get(0).getProgram().getUserActivity().getActivity();

            // Un marqueur par (activité, lieu) : les créneaux sont regroupés sur des
            // coordonnées arrondies à 3 décimales (~111 m). La règle exacte est
            // documentée sur MapActivityMarkerDto — un client qui déduplique doit la
            // reproduire, et Math.round arrondit les demis vers +∞, pas à l'opposé
            // de zéro comme le font Dart et Python.
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

                // programCount comptait locationSchedules.size(), c'est-à-dire des
                // créneaux : un programme unique à trois séances hebdomadaires
                // s'affichait « 3 programmes », y compris dans la confirmation de
                // suppression d'une activité. Le nombre de créneaux reste exposé,
                // sous son vrai nom.
                int programCount = (int) locationSchedules.stream()
                    .map(s -> s.getProgram().getId())
                    .distinct()
                    .count();

                markers.add(new MapActivityMarkerDto(
                    activity.getId(),
                    activity.getName(),
                    activity.getSlug(),
                    activity.getCategory() != null ? activity.getCategory().getId() : null,
                    activity.getCategory() != null ? activity.getCategory().getName() : null,
                    activity.getCategory() != null ? activity.getCategory().getIcon() : null,
                    activity.getCategory() != null ? activity.getCategory().getColorRamp() : null,
                    lat,
                    lng,
                    distanceKm,
                    programCount,
                    locationSchedules.size(),
                    repUser != null ? repUser.getId() : null,
                    organizerName,
                    organizerAvatarUrl,
                    nextSessionAt,
                    address
                ));
            }
        }

        // 5. Ordre total déterministe : sans lui, une troncature garderait des
        // marqueurs arbitraires et deux appels identiques pourraient différer.
        markers.sort(markerOrder(userLat, userLng));

        // 6. Filtre « séance à venir », avant toute agrégation. Voir
        // keepUpcoming : c'est ce qui fait qu'un count de cluster compte des
        // marqueurs réellement affichables.
        if (request.filterToUpcoming()) {
            markers = keepUpcoming(markers);
        }

        // 7. Agrégation optionnelle. totalInBounds est figé avant, pour que la
        // somme des count des clusters et de la liste restante lui soit égale.
        int totalInBounds = markers.size();
        List<MapCluster> clusters = List.of();
        if (request.zoom() != null) {
            ClusteredMarkers clustered = clusterMarkers(markers, request.zoom());
            clusters = clustered.clusters();
            markers = clustered.loose();
        }

        // 8. Troncature explicite, jamais silencieuse. Elle ne porte que sur les
        // marqueurs non agrégés : un cluster est déjà une réduction de volume.
        int effectiveLimit = effectiveLimit(request);
        boolean truncated = markers.size() > effectiveLimit;
        if (truncated) {
            markers = new ArrayList<>(markers.subList(0, effectiveLimit));
        }

        // 9. Calculate default center (area with most activities)
        MapActivitiesResponse.DefaultMapCenter defaultCenter = calculateDefaultCenter(markers);

        return new MapActivitiesResponse(markers, defaultCenter, truncated, totalInBounds, clusters);
    }

    /**
     * Charge les créneaux localisés, bornés en base quand un filtre est demandé.
     *
     * <p>Sans filtre, on retombe sur la requête historique : un client déployé
     * obtient exactement ce qu'il obtenait.
     */
    private List<Schedule> loadSchedules(MapActivitiesRequest request) {
        Set<UUID> categoryIds = request.effectiveCategoryIds();
        boolean filterByCategory = !categoryIds.isEmpty();

        if (!request.hasGeoFilter() && !filterByCategory) {
            return scheduleRepository.findAllWithActivityDetails();
        }

        List<UUID> ids = scheduleRepository.findLocatedScheduleIdsWithin(
            request.userLat(), request.userLng(), request.radiusMeters(),
            request.north(), request.south(), request.east(), request.west(),
            filterByCategory,
            filterByCategory ? categoryIds : ScheduleRepository.NO_CATEGORY_FILTER);

        return ids.isEmpty() ? List.of() : scheduleRepository.findWithActivityDetailsByIds(ids);
    }

    /**
     * Distance croissante quand elle est connue, puis activityId, puis
     * coordonnées : deux marqueurs ne peuvent pas rester à égalité, donc l'ordre
     * est total. Sans position utilisateur, distanceKm est null partout et le
     * tri se réduit à activityId puis coordonnées.
     */
    private Comparator<MapActivityMarkerDto> markerOrder(Double userLat, Double userLng) {
        Comparator<MapActivityMarkerDto> byDistance = Comparator.comparingDouble(
            m -> m.distanceKm() != null ? m.distanceKm() : Double.MAX_VALUE);
        return byDistance
            .thenComparing(m -> m.activityId().toString())
            .thenComparingDouble(MapActivityMarkerDto::lat)
            .thenComparingDouble(MapActivityMarkerDto::lng);
    }

    /**
     * Ne garde que les marqueurs ayant une séance à venir — <b>avant</b> que
     * l'agrégation ne les compte.
     *
     * <p>La règle produit est « la carte ne montre que les activités qui ont un
     * programme et au moins une séance à venir ». Le client la tenait seul, sur
     * les seuls marqueurs isolés : un cluster ne porte que {@code count}, jamais
     * les champs à tester. D'où une pastille annonçant 12 qui se défaisait en 7
     * au zoom — la même carte avec deux règles, dont une seule applicable.
     *
     * <p><b>Le test porte sur {@code nextSessionAt}, le champ que le DTO expose
     * déjà</b>, et non sur une expiration recalculée ici. C'est délibéré : le
     * {@code count} d'un cluster devient ainsi, par construction, « ce que le
     * client aurait gardé ». Recalculer, même mieux, ferait vivre une troisième
     * définition de l'expiration — exactement ce que la demande cherche à
     * supprimer.
     *
     * <p>La seconde condition, « a au moins un programme », n'est pas testée :
     * elle est structurellement vraie sur cette route. Un marqueur naît d'un
     * créneau localisé rattaché à un programme, et {@code programCount} compte
     * les programmes distincts de ces créneaux — il vaut donc toujours au moins
     * un. Un test l'affirme, pour que cette invariance cesse d'être tacite.
     *
     * <p>Limite héritée, non introduite ici : {@code nextSessionAt} se lit sur
     * {@code starts_at} sans dérouler la règle de récurrence. C'est
     * {@link org.program.pair.domain.program.jobs.RecurringSlotRolloverJob} qui
     * maintient {@code starts_at} sur la prochaine occurrence réelle, à dix
     * minutes près. Pendant cette fenêtre, une activité récurrente vivante paraît
     * expirée — elle disparaissait déjà de l'app avant ce filtre, puisque le
     * client écartait le marqueur ; elle disparaît désormais de la réponse.
     */
    private List<MapActivityMarkerDto> keepUpcoming(List<MapActivityMarkerDto> markers) {
        return markers.stream()
            .filter(m -> m.nextSessionAt() != null)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /** Résultat d'une agrégation : les clusters, et les marqueurs restés seuls. */
    private record ClusteredMarkers(List<MapCluster> clusters, List<MapActivityMarkerDto> loose) {}

    /**
     * Agrège les marqueurs proches selon la maille du zoom.
     *
     * <p>Une cellule portant au moins deux marqueurs devient un cluster ; une
     * cellule seule laisse son marqueur intact. Les deux listes sont donc
     * disjointes, et {@code somme(count) + loose.size()} vaut le nombre de
     * marqueurs entrants — c'est ce qui rend {@code totalInBounds} vérifiable
     * par le client.
     *
     * <p>Corollaire : plus le zoom est élevé, plus la maille est fine, moins il
     * reste de cellules peuplées — au zoom maximal (~43 m, cf.
     * {@link #calculateGridSize}), des activités distinctes tombent en pratique
     * dans des cellules distinctes et il ne reste que des marqueurs. Ce n'est pas
     * une garantie absolue : deux activités séparées par moins que la maille
     * restent agrégées, ce qui est le comportement voulu.
     *
     * <p>L'ordre d'entrée est préservé dans {@code loose} et détermine l'ordre
     * des clusters (première apparition), donc l'agrégation ne réintroduit pas
     * l'indéterminisme que le tri vient d'éliminer.
     */
    private ClusteredMarkers clusterMarkers(List<MapActivityMarkerDto> markers, int zoom) {
        double gridSize = calculateGridSize(zoom);

        Map<String, List<MapActivityMarkerDto>> grid = new LinkedHashMap<>();
        for (MapActivityMarkerDto marker : markers) {
            String cell = (int) Math.floor(marker.lat() / gridSize)
                + "," + (int) Math.floor(marker.lng() / gridSize);
            grid.computeIfAbsent(cell, k -> new ArrayList<>()).add(marker);
        }

        List<MapCluster> clusters = new ArrayList<>();
        List<MapActivityMarkerDto> loose = new ArrayList<>();

        for (List<MapActivityMarkerDto> cell : grid.values()) {
            if (cell.size() < 2) {
                loose.addAll(cell);
                continue;
            }
            List<double[]> points = cell.stream()
                .map(m -> new double[]{m.lat(), m.lng()})
                .toList();
            // Les identifiants des membres voyagent avec le cluster : c'est
            // l'information que la réponse avait déjà en main, et pour laquelle le
            // client refaisait un GET /activities/browse centré sur la pastille.
            List<UUID> memberActivityIds = cell.stream()
                .map(MapActivityMarkerDto::activityId)
                .distinct()
                .toList();
            clusters.add(MapCluster.of(points, "cluster", dominantCategoryIcon(cell), memberActivityIds));
        }

        return new ClusteredMarkers(clusters, loose);
    }

    /** Icône la plus représentée parmi les membres, pour dessiner le cluster. */
    private String dominantCategoryIcon(List<MapActivityMarkerDto> cell) {
        return cell.stream()
            .map(MapActivityMarkerDto::categoryIcon)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(icon -> icon, Collectors.counting()))
            .entrySet().stream()
            // Départage sur l'icône elle-même : deux catégories à égalité ne
            // doivent pas donner un résultat différent d'un appel à l'autre.
            .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /** Plafond effectif : celui demandé, ramené au plafond dur ; sinon aucun. */
    private int effectiveLimit(MapActivitiesRequest request) {
        if (request.limit() == null) {
            return Integer.MAX_VALUE;
        }
        return Math.min(request.limit(), MAX_MARKER_LIMIT);
    }

    /**
     * Rejette les combinaisons de paramètres qui n'ont pas de sens, avec un code
     * stable par cas — un 400 « mauvaise requête » sans code obligerait le client
     * à lire un message français pour savoir quoi corriger.
     *
     * <p>Appelé hors du try/catch de {@link #getAllActivitiesForMap} : celui-ci
     * avale toute exception pour renvoyer une carte vide, ce qui transformerait
     * une erreur de paramètre en réponse 200 silencieusement vide.
     */
    private void validate(MapActivitiesRequest request) {
        if (request.radiusMeters() != null) {
            if (request.userLat() == null || request.userLng() == null) {
                throw new ValidationException(ErrorCode.MAP_RADIUS_REQUIRES_USER_LOCATION,
                    "Le paramètre 'radiusMeters' exige 'userLat' et 'userLng'.");
            }
            if (request.radiusMeters() < MIN_RADIUS_METERS || request.radiusMeters() > MAX_RADIUS_METERS) {
                throw new ValidationException(ErrorCode.MAP_RADIUS_OUT_OF_RANGE,
                    "Le paramètre 'radiusMeters' doit être compris entre "
                        + MIN_RADIUS_METERS + " et " + MAX_RADIUS_METERS + ".");
            }
        }

        if (request.hasBounds()) {
            if (request.north() == null || request.south() == null
                    || request.east() == null || request.west() == null) {
                throw new ValidationException(ErrorCode.MAP_BOUNDS_INCOMPLETE,
                    "Les bornes 'north', 'south', 'east' et 'west' vont ensemble : "
                        + "fournissez les quatre ou aucune.");
            }
            if (request.south() > request.north()) {
                throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                    "'south' ne peut pas être supérieur à 'north'.");
            }
            // Une bbox à cheval sur l'antiméridien demanderait deux enveloppes ;
            // le cas n'est pas supporté, autant le dire plutôt que renvoyer vide.
            if (request.west() > request.east()) {
                throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                    "'west' ne peut pas être supérieur à 'east' : une zone à cheval "
                        + "sur l'antiméridien n'est pas supportée.");
            }
            if (request.south() < -90 || request.north() > 90
                    || request.west() < -180 || request.east() > 180) {
                throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                    "Les bornes doivent rester dans [-90, 90] en latitude et "
                        + "[-180, 180] en longitude.");
            }
        }

        if (request.limit() != null && request.limit() < 1) {
            throw new ValidationException(ErrorCode.MAP_LIMIT_OUT_OF_RANGE,
                "Le paramètre 'limit' doit être supérieur ou égal à 1.");
        }

        if (request.zoom() != null && (request.zoom() < 1 || request.zoom() > 20)) {
            throw new ValidationException(ErrorCode.MAP_ZOOM_OUT_OF_RANGE,
                "Le paramètre 'zoom' doit être compris entre 1 et 20.");
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
