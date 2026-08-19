package org.program.pair.repository;

import org.program.pair.domain.activity.Activity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    Optional<Activity> findBySlug(String slug);

    Page<Activity> findByCategoryId(UUID categoryId, Pageable pageable);

    Page<Activity> findByNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Activity> findByCategoryIdAndNameContainingIgnoreCase(
        UUID categoryId, String name, Pageable pageable);

    @Query("SELECT a FROM Activity a WHERE " +
           "(:categoryId IS NULL OR a.category.id = :categoryId) AND " +
           "(:search IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Activity> searchActivities(
        @Param("categoryId") UUID categoryId,
        @Param("search") String search,
        Pageable pageable
    );

    boolean existsBySlug(String slug);

    boolean existsByCategoryIdAndNameIgnoreCase(UUID categoryId, String name);

    @Query(value = "SELECT * FROM activities WHERE embedding IS NULL", nativeQuery = true)
    List<Activity> findByEmbeddingIsNull();

    /** Ligne d'agrégat des deux requêtes de suggestion. */
    interface SuggestedActivityRow {
        UUID getId();
        String getName();
        String getSlug();
        String getIcon();
        String getImageUrl();
        UUID getCategoryId();
        String getCategoryName();
        long getPractitioners();
    }

    /**
     * Activités les plus déclarées autour d'une position.
     *
     * <p>L'ancrage est la position <b>des personnes</b> et non celle des créneaux :
     * la question posée est « qu'est-ce qui se pratique ici », pas « où a lieu la
     * prochaine séance ». C'est la même maille que {@code findVisibleInRadius},
     * et elle hérite de ses trois conditions de visibilité — activité montrée sur
     * la carte, compte actif, position rendue publique. Une personne qui masque
     * sa position ne compte donc pas, ce qui est cohérent : elle n'apparaît nulle
     * part ailleurs non plus.
     *
     * <p>Les activités que l'appelant a déjà déclarées sont écartées : proposer à
     * quelqu'un ce qu'il pratique déjà n'est pas une suggestion.
     */
    @Query(value = """
        SELECT a.id            AS id,
               a.name          AS name,
               a.slug          AS slug,
               a.icon          AS icon,
               a.image_url     AS imageUrl,
               c.id            AS categoryId,
               c.name          AS categoryName,
               COUNT(DISTINCT ua.user_id) AS practitioners
        FROM user_activities ua
        JOIN users u      ON ua.user_id = u.id
        JOIN activities a ON ua.activity_id = a.id
        JOIN categories c ON a.category_id = c.id
        WHERE ua.visible_on_map = true
          AND u.is_active = true
          AND u.location_public = true
          AND u.location IS NOT NULL
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
          AND NOT EXISTS (
              SELECT 1 FROM user_activities mine
              WHERE mine.user_id = :requesterId AND mine.activity_id = a.id
          )
        GROUP BY a.id, a.name, a.slug, a.icon, a.image_url, c.id, c.name
        ORDER BY practitioners DESC, a.name ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<SuggestedActivityRow> findMostPractisedInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("requesterId") UUID requesterId,
        @Param("limit") int limit
    );

    /**
     * Repli quand le voisinage ne donne rien : les activités les plus déclarées,
     * toutes positions confondues.
     *
     * <p>Sans condition géographique, mais avec les mêmes conditions de
     * visibilité — une suggestion ne doit pas révéler ce que la carte cache. Le
     * décompte n'est pas exposé au client dans ce cas : il ne dit rien du
     * voisinage, et l'afficher laisserait croire le contraire.
     */
    @Query(value = """
        SELECT a.id            AS id,
               a.name          AS name,
               a.slug          AS slug,
               a.icon          AS icon,
               a.image_url     AS imageUrl,
               c.id            AS categoryId,
               c.name          AS categoryName,
               COUNT(DISTINCT ua.user_id) AS practitioners
        FROM activities a
        JOIN categories c ON a.category_id = c.id
        LEFT JOIN user_activities ua ON ua.activity_id = a.id AND ua.visible_on_map = true
        LEFT JOIN users u ON ua.user_id = u.id AND u.is_active = true
        WHERE NOT EXISTS (
              SELECT 1 FROM user_activities mine
              WHERE mine.user_id = :requesterId AND mine.activity_id = a.id
          )
        GROUP BY a.id, a.name, a.slug, a.icon, a.image_url, c.id, c.name
        ORDER BY practitioners DESC, a.name ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<SuggestedActivityRow> findMostPractisedGlobally(
        @Param("requesterId") UUID requesterId,
        @Param("limit") int limit
    );

    @Modifying
    @Query(value = "UPDATE activities SET embedding = CAST(:embedding AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingVectorString);

    /**
     * Activités de la même catégorie (proxy pratique de proximité sémantique)
     * ayant au moins une personne visible sur la carte à proximité. Utilisé
     * pour transformer un résultat de recherche vide en alternative concrète.
     */
    @Query(value = """
        SELECT a.* FROM activities a
        WHERE a.id <> :activityId
          AND a.category_id = (SELECT category_id FROM activities WHERE id = :activityId)
          AND EXISTS (
              SELECT 1 FROM user_activities ua
              JOIN users u ON u.id = ua.user_id
              WHERE ua.activity_id = a.id
                AND ua.visible_on_map = TRUE
                AND u.is_active = TRUE
                AND u.location_public = TRUE
                AND ST_DWithin(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    :radiusMeters)
          )
        LIMIT :limit
        """, nativeQuery = true)
    List<Activity> findSimilarActivitiesWithNearbyUsers(
        @Param("activityId") UUID activityId,
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );
}
