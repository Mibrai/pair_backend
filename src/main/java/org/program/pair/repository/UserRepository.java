package org.program.pair.repository;

import org.program.pair.domain.block.BlockSql;
import org.program.pair.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        """ + BlockSql.NOT_BLOCKED_U + """
        ORDER BY ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
        )
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> findVisibleUsersInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("viewerId") UUID viewerId
    );

    /**
     * Utilisateurs visibles à l'intérieur d'une bbox.
     *
     * <p>Remplace, pour {@code GET /map/bounds}, l'approximation « rayon déduit
     * de la bbox puis filtre en Java » : ce rayon valait
     * {@code max(latDiff, lngDiff) * 111320 / 2}, plafonné à 100 km. Un disque
     * inscrit ne couvre pas les coins du rectangle, et le plafond rognait
     * silencieusement les grandes zones — des utilisateurs pourtant dans les
     * bornes n'étaient jamais renvoyés.
     *
     * <p>Tri par distance au centre de la bbox : {@code limit} garde alors les
     * plus proches du centre de l'écran, pas un échantillon arbitraire. Le
     * départage sur l'id rend l'ordre total, donc la pagination stable.
     */
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND u.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
        """ + BlockSql.NOT_BLOCKED_U + """
        ORDER BY ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint((:west + :east) / 2, (:south + :north) / 2), 4326)::geography
        ), u.id
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> findVisibleUsersInBounds(
        @Param("south") double south,
        @Param("north") double north,
        @Param("west") double west,
        @Param("east") double east,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("viewerId") UUID viewerId
    );

    /** Total exact avant application de {@code limit}, pour {@code totalInBounds}. */
    @Query(value = """
        SELECT COUNT(*) FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND u.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
        """ + BlockSql.NOT_BLOCKED_U + """
        """, nativeQuery = true)
    long countVisibleUsersInBounds(
        @Param("south") double south,
        @Param("north") double north,
        @Param("west") double west,
        @Param("east") double east,
        @Param("viewerId") UUID viewerId
    );

    @Query("SELECT u FROM User u WHERE u.id IN :ids AND u.lastActiveAt > :since AND u.onlineStatusVisible = true")
    List<User> findOnlineUsers(@Param("ids") List<UUID> ids, @Param("since") Instant since);

    /**
     * Find inactive accounts for GDPR purge (Article 17)
     * Accounts that have been inactive for more than 30 days
     */
    @Query("SELECT u FROM User u WHERE u.isActive = false AND u.lastActiveAt < :cutoff")
    List<User> findInactiveAccountsBefore(@Param("cutoff") Instant cutoff);

    /**
     * Le corps de la recherche de personnes — visibilité, correspondance,
     * bornage géographique — écrit une fois pour la page et pour son compte.
     *
     * <p>Les deux requêtes doivent voir exactement la même population : un
     * {@code COUNT} qui compterait autrement rendrait une pagination dont la
     * dernière page est vide, ou qui en cache une.
     *
     * <p><b>Trois défauts du 04/09 vivent dans ce {@code WHERE}, et voici ce
     * qu'ils sont devenus.</b>
     *
     * <p>1. <b>Qui est trouvable.</b> La clause était {@code location_public =
     * true}, seule et inconditionnelle. Or ce champ vaut {@code FALSE} par
     * défaut (V2) et n'est écrit que par {@code PUT /api/users/me} : l'écran de
     * confidentialité, lui, pose {@code show_location} et {@code show_on_map} et
     * ne le touche jamais. Activer « Localisation publique » dans l'application
     * ne rendait donc personne trouvable, et les seuls comptes qui remontaient
     * étaient ceux du semeur de démonstration, qui pose le champ à la main.
     * Trois réglages disent la même chose à l'utilisateur ; la recherche les
     * accepte désormais tous les trois. {@code IS TRUE} et non {@code = TRUE} :
     * les deux colonnes de V16 sont nullables.
     *
     * <p>2. <b>Les accents.</b> {@code unaccent} des deux côtés de la
     * comparaison — sans quoi « muller » ne trouvait pas « Müller », la
     * recherche la plus banale qui soit. L'extension est installée par V101.
     * Elle n'est pas immuable, donc inutilisable dans un index ; cela ne coûte
     * rien ici, un {@code LIKE '%…%'} n'en utilisait déjà aucun.
     *
     * <p>3. <b>Ce que les gens organisent.</b> Le {@code LIKE} ne portait que
     * sur le nom et la bio : chercher le titre d'une soirée ne trouvait pas
     * celui qui l'organise. L'{@code EXISTS} ajoute les titres de programmes —
     * et il couvre aussi les créneaux, dont le titre <i>est</i> celui de leur
     * programme, fabriqué par {@code QuickSlotService.titleFor}.
     *
     * <p>L'{@code EXISTS} ne retient que les programmes actifs et publics :
     * rendre quelqu'un trouvable par le titre d'un programme que personne ne
     * peut voir ferait fuiter l'existence de ce programme.
     *
     * <p>4. <b>On ne se trouve pas soi-même.</b> Aucune exclusion de l'appelant
     * n'existait, contrairement aux autres lectures de personnes : se chercher
     * par son propre nom se rendait soi-même, et un onglet qui sert à trouver
     * quelqu'un à suivre proposait de se suivre. L'exclusion entre dans le
     * {@code WHERE} et non après coup, pour que le compte reste d'accord avec la
     * page — un post-filtrage annoncerait un total qu'il ne rend pas, et la
     * dernière page serait vide sans le dire.
     */
    String SEARCH_USERS_BODY = """
        FROM users u
        WHERE u.is_active = true
          AND (CAST(:viewerId AS uuid) IS NULL OR u.id <> :viewerId)
          AND (u.location_public IS TRUE
               OR u.show_location IS TRUE
               OR u.show_on_map IS TRUE)
          AND (
            unaccent(LOWER(u.display_name)) LIKE unaccent(LOWER(CONCAT('%', :query, '%')))
            OR unaccent(LOWER(u.bio)) LIKE unaccent(LOWER(CONCAT('%', :query, '%')))
            OR EXISTS (
                SELECT 1 FROM user_activities ua
                JOIN programs p ON p.user_activity_id = ua.id
                WHERE ua.user_id = u.id
                  AND p.status = 'ACTIVE'
                  AND p.is_public = TRUE
                  AND unaccent(LOWER(p.title)) LIKE unaccent(LOWER(CONCAT('%', :query, '%')))
            )
          )
          AND (:lat IS NULL OR :lng IS NULL OR ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          ))
        """ + BlockSql.NOT_BLOCKED_U;

    /**
     * La page de résultats.
     *
     * <p><b>{@code u.id} départage le classement</b>, et ce n'est pas une
     * précaution de style. Sans position, la clé de tri vaut {@code 0} pour
     * toutes les lignes : l'ordre était alors laissé au hasard du plan
     * d'exécution, et deux pages successives pouvaient se recouvrir ou se
     * manquer. C'est précisément le cas de l'onglet « Trouver », qui n'envoie
     * jamais de position.
     */
    @Query(value = "SELECT u.* " + SEARCH_USERS_BODY + """
        ORDER BY
          CASE WHEN :lat IS NULL OR :lng IS NULL THEN 0
          ELSE ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
          )
          END,
          u.id
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> searchUsers(
        @Param("query") String query,
        @Param("lat") Double lat,
        @Param("lng") Double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("viewerId") UUID viewerId
    );

    /** Le total de la recherche, sur exactement le même {@code WHERE} que la page. */
    @Query(value = "SELECT COUNT(*) " + SEARCH_USERS_BODY, nativeQuery = true)
    long countSearchResults(
        @Param("query") String query,
        @Param("lat") Double lat,
        @Param("lng") Double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("viewerId") UUID viewerId
    );
}
