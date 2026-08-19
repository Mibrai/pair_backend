package org.program.pair.domain.activity;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.ActivityBrowseRequest;
import org.program.pair.domain.activity.dto.ActivityFacetsDto;
import org.program.pair.repository.ActivityFacetRow;
import org.program.pair.domain.activity.dto.BrowsedActivityDto;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.repository.ActivityBrowseRow;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L'Explorer, côté serveur.
 *
 * <p>Remplace une jointure faite dans le client, qui indexait les programmes
 * <b>par nom d'activité normalisé</b> : deux « Yoga » d'organisateurs différents
 * fusionnaient, « Yôga » et « Yoga » se séparaient, et l'organisateur n'était
 * cliquable que si le nom s'appariait. La clé est ici la vraie clé étrangère.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityBrowseService {

    private static final int MIN_RADIUS_METERS = 1;
    private static final int MAX_RADIUS_METERS = 200_000;
    private static final int MAX_PAGE_SIZE = 100;
    /** Bornage de la liste imbriquée : la page de détail n'en montre pas plus. */
    private static final int MAX_NESTED_PROGRAMS = 3;

    private final UserActivityRepository userActivityRepository;
    private final ProgramRepository programRepository;
    private final SubscriptionService subscriptionService;

    /**
     * @param requesterId appelant, ou {@code null} : {@code subscribed} vaut alors
     *                    {@code false} faute d'identité, jamais faute d'abonnement
     */
    public Page<BrowsedActivityDto> browse(ActivityBrowseRequest request, UUID requesterId) {
        validate(request);

        Page<ActivityBrowseRow> rows = userActivityRepository.browse(
            request.lat(), request.lng(),
            request.effectiveRadiusMeters(),
            request.effectiveIncludeExpired(),
            toUuidArrayLiteral(request.categoryIds()),
            toTextArrayLiteral(request.activityLevels()),
            request.sortByNextSession(),
            // Sans appelant identifié, les deux filtres personnels ne
            // s'appliquent pas : il n'y a ni activités ni abonnements à
            // comparer. Les appliquer quand même aurait rendu une liste vide,
            // c'est-à-dire « rien autour de vous » au lieu de « connectez-vous ».
            requesterId != null && request.effectiveMyActivitiesOnly(),
            requesterId != null && request.effectiveSubscribedOnly(),
            requesterId == null ? null : requesterId.toString(),
            // Sort.unsorted() volontairement : l'ordre total est dans la requête,
            // laisser Spring en injecter un second le contredirait.
            PageRequest.of(request.effectivePage(), request.effectiveSize()));

        List<UUID> entryIds = rows.getContent().stream()
            .map(ActivityBrowseRow::getUserActivityId)
            .toList();

        Map<UUID, List<BrowsedActivityDto.BrowsedProgramDto>> programsByEntry =
            request.effectiveIncludePrograms() ? loadPrograms(entryIds) : Map.of();

        // Compteurs et état d'abonnement : deux requêtes bornées à la page, et
        // non une modification de la requête native de browse(...). Celle-ci est
        // déjà lourde, et son mapping par alias casse silencieusement quand on y
        // touche — le coût de l'enrichissement est constant par page, celui d'un
        // SQL natif remanié ne l'est pas.
        Map<UUID, Long> subscriberCounts = subscriptionService.countUserActivitySubscribers(entryIds);
        Set<UUID> subscribedTo = subscriptionService.subscribedUserActivityIds(requesterId, entryIds);

        return rows.map(row -> toDto(
            row,
            subscriberCounts.getOrDefault(row.getUserActivityId(), 0L),
            subscribedTo.contains(row.getUserActivityId()),
            programsByEntry.get(row.getUserActivityId())));
    }

    /**
     * Les compteurs des filtres, pour la même zone.
     *
     * <p>Sert le panneau de filtres : « Débutant (12) », « Mes activités (4) ».
     * C'est ce que la spécification appelle « rétablir les compteurs » — ils
     * existaient tant que le client filtrait les pages déjà chargées, et ont
     * disparu en même temps que ce filtrage.
     *
     * <p>Route séparée, et non un enrichissement de la réponse paginée. Celle-ci
     * est un {@code Page<BrowsedActivityDto>} qu'une version publiée du client
     * consomme déjà : l'envelopper pour y loger les facettes aurait cassé ce
     * contrat, alors qu'une route de plus n'enlève rien à personne. Le client
     * l'appelle quand il ouvre ses filtres, pas à chaque page.
     */
    public ActivityFacetsDto facets(ActivityBrowseRequest request, UUID requesterId) {
        validate(request);

        List<ActivityFacetRow> rows = userActivityRepository.browseFacets(
            request.lat(), request.lng(),
            request.effectiveRadiusMeters(),
            request.effectiveIncludeExpired(),
            toUuidArrayLiteral(request.categoryIds()),
            requesterId == null ? null : requesterId.toString());

        Map<String, Long> byLevel = new LinkedHashMap<>();
        long total = 0;
        long mine = 0;
        long subscribed = 0;

        for (ActivityFacetRow row : rows) {
            // Un niveau nul est une absence de déclaration, pas un niveau
            // « ANY » : la clé le dit, et la ligne compte quand même au total.
            byLevel.merge(row.getLevel() == null ? "UNSPECIFIED" : row.getLevel(),
                row.getTotal(), Long::sum);
            total += row.getTotal();
            mine += row.getMineCount();
            subscribed += row.getSubscribedCount();
        }

        return new ActivityFacetsDto(total, byLevel, mine, subscribed);
    }

    private BrowsedActivityDto toDto(ActivityBrowseRow row,
                                      long subscriberCount,
                                      boolean subscribed,
                                      List<BrowsedActivityDto.BrowsedProgramDto> programs) {
        return new BrowsedActivityDto(
            row.getUserActivityId(),
            row.getActivityId(),
            row.getActivityName(),
            row.getActivityIcon(),
            row.getImageUrl(),
            row.getDescription(),
            row.getCategoryId(),
            row.getCategoryName(),
            row.getCategoryIcon(),
            row.getLat(),
            row.getLng(),
            row.getAddress(),
            row.getDistanceMeters(),
            row.getLocationType(),
            row.getOrganizerId(),
            row.getOrganizerName(),
            row.getOrganizerAvatarUrl(),
            row.getProgramCount(),
            row.getTotalParticipants(),
            row.getNextSessionAt(),
            row.getIsExpired(),
            subscriberCount,
            subscribed,
            programs
        );
    }

    /**
     * Programmes des entrées de la page, en une requête pour toute la page — pas
     * une par entrée. Bornés aux {@value #MAX_NESTED_PROGRAMS} prochains : une
     * entrée à quarante programmes ne doit pas gonfler la réponse d'une liste que
     * personne n'affiche.
     */
    private Map<UUID, List<BrowsedActivityDto.BrowsedProgramDto>> loadPrograms(List<UUID> userActivityIds) {
        if (userActivityIds.isEmpty()) {
            return Map.of();
        }
        return programRepository.findActiveWithEnrolmentsByUserActivityIds(userActivityIds).stream()
            .collect(Collectors.groupingBy(
                row -> ((Program) row[0]).getUserActivity().getId(),
                Collectors.collectingAndThen(Collectors.toList(), this::toNestedPrograms)));
    }

    private List<BrowsedActivityDto.BrowsedProgramDto> toNestedPrograms(List<Object[]> rows) {
        return rows.stream()
            // Les programmes sans prochaine séance passent après ceux qui en ont
            // une ; départage sur l'id pour que la liste soit stable.
            .sorted(Comparator
                .comparing((Object[] row) -> ((Program) row[0]).getNextSessionAt() == null)
                .thenComparing(row -> ((Program) row[0]).getNextSessionAt(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> ((Program) row[0]).getId().toString()))
            .limit(MAX_NESTED_PROGRAMS)
            .map(row -> {
                Program p = (Program) row[0];
                long enrolled = (Long) row[1];
                return new BrowsedActivityDto.BrowsedProgramDto(
                    p.getId(),
                    p.getTitle(),
                    p.getUserActivity() != null && p.getUserActivity().getLevel() != null
                        ? p.getUserActivity().getLevel().name() : null,
                    (int) enrolled,
                    p.getNextSessionAt());
            })
            .toList();
    }

    private void validate(ActivityBrowseRequest request) {
        if (request.lat() < -90 || request.lat() > 90) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR,
                "Le paramètre 'lat' doit être compris entre -90 et 90.");
        }
        if (request.lng() < -180 || request.lng() > 180) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR,
                "Le paramètre 'lng' doit être compris entre -180 et 180.");
        }
        int radius = request.effectiveRadiusMeters();
        if (radius < MIN_RADIUS_METERS || radius > MAX_RADIUS_METERS) {
            throw new ValidationException(ErrorCode.MAP_RADIUS_OUT_OF_RANGE,
                "Le paramètre 'radiusMeters' doit être compris entre "
                    + MIN_RADIUS_METERS + " et " + MAX_RADIUS_METERS + ".");
        }
        if (request.effectivePage() < 0) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR,
                "Le paramètre 'page' est indexé à 0 et ne peut pas être négatif.");
        }
        if (request.effectiveSize() < 1 || request.effectiveSize() > MAX_PAGE_SIZE) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR,
                "Le paramètre 'size' doit être compris entre 1 et " + MAX_PAGE_SIZE + ".");
        }
    }

    /**
     * Les filtres facultatifs passent en littéral de tableau Postgres plutôt
     * qu'en {@code IN (...)} : une liste vide devient {@code NULL}, ce qui
     * neutralise le filtre dans la requête sans avoir à en écrire deux variantes.
     */
    private String toUuidArrayLiteral(List<UUID> ids) {
        return (ids == null || ids.isEmpty())
            ? null
            : "{" + ids.stream().map(UUID::toString).collect(Collectors.joining(",")) + "}";
    }

    private String toTextArrayLiteral(List<String> values) {
        return (values == null || values.isEmpty())
            ? null
            : "{" + values.stream().map(v -> "\"" + v.replace("\"", "") + "\"")
                .collect(Collectors.joining(",")) + "}";
    }
}
