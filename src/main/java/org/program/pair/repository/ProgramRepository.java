package org.program.pair.repository;

import org.program.pair.domain.chat.dto.ProgramMessagingPolicy;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProgramRepository extends JpaRepository<Program, UUID> {

    /**
     * Le programme désigné par son jeton public, ou rien.
     *
     * <p>C'est la seule recherche que fait le partage public, et elle passe par
     * l'index d'unicité de la colonne — jamais par l'identifiant interne, qu'une
     * adresse publique ne doit pas exposer.
     */
    java.util.Optional<Program> findByPublicShareToken(String publicShareToken);

    boolean existsByPublicShareToken(String publicShareToken);

    /**
     * Incrémente le compteur d'ouvertures d'une page publique de programme.
     *
     * <p>Un {@code UPDATE} atomique, et non une lecture suivie d'une écriture :
     * deux ouvertures simultanées du même lien — ce que le partage dans un groupe
     * produit précisément — n'en compteraient qu'une.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Program p SET p.publicViewCount = p.publicViewCount + 1 "
        + "WHERE p.publicShareToken = :token")
    int incrementPublicViewCount(@Param("token") String token);


    @Query("SELECT COUNT(p) FROM Program p WHERE p.userActivity.user.id = :userId")
    long countProgramsByUser(@Param("userId") UUID userId);

    /**
     * Auteur du programme et réglage d'autorisation des messages, en une requête.
     *
     * <p>Sert les refus de {@code ChatService}. Charger l'entité obligerait à
     * traverser {@code userActivity} puis {@code user}, tous deux paresseux,
     * pour n'en tirer qu'un identifiant et un booléen.
     */
    @Query("SELECT new org.program.pair.domain.chat.dto.ProgramMessagingPolicy(" +
           "  p.id, p.userActivity.user.id, p.allowParticipantMessages) " +
           "FROM Program p WHERE p.id = :programId")
    Optional<ProgramMessagingPolicy> findMessagingPolicy(@Param("programId") UUID programId);

    /**
     * Programmes actifs et publics de plusieurs {@code UserActivity}, avec leur
     * nombre d'inscrits actifs — pour {@code /activities/browse?includePrograms=true}.
     *
     * <p>Une seule requête pour toute la page : une par entrée ferait vingt
     * allers-retours sur un écran de liste.
     */
    @Query("""
        SELECT p, (SELECT COUNT(up) FROM UserProgram up
                   WHERE up.program = p AND up.status = 'ACTIVE')
        FROM Program p
        JOIN FETCH p.userActivity ua
        WHERE ua.id IN :userActivityIds
          AND p.status = 'ACTIVE'
          AND p.isPublic = true
        """)
    List<Object[]> findActiveWithEnrolmentsByUserActivityIds(
        @Param("userActivityIds") List<UUID> userActivityIds);

    /**
     * Programmes sémantiquement proches de la requête, <b>bornés sur le lieu de
     * leurs séances</b> et non sur le domicile de leur organisateur.
     *
     * <p>Le filtre portait sur {@code u.location}, la position du compte. Un
     * programme dont les séances se tiennent à deux kilomètres de l'utilisateur
     * était donc absent d'une recherche à cinq kilomètres dès que son
     * organisateur habitait ailleurs — et l'absence, contrairement à une
     * distance fausse, ne se voit pas. Un programme dont l'organisateur n'avait
     * aucune position n'était jamais rendu.
     *
     * <p>Un programme entre désormais dans le rayon dès qu'<b>une</b> de ses
     * séances localisées y est. Le corollaire est assumé : un programme à
     * plusieurs lieux peut entrer par n'importe lequel, et
     * {@code SemanticSearchService} le situera ensuite sur le plus proche du
     * point interrogé.
     *
     * <p>Principe du filtre : <b>un rayon ne peut exclure que ce qu'on sait
     * situer.</b> Un programme à distance ({@code REMOTE}, {@code ONLINE}), ou
     * sans aucune séance localisée, y échappe donc plutôt que d'y échouer — il
     * est rendu, et {@code SemanticSearchService} lui donnera des coordonnées
     * nulles. Le borner sur la position de son organisateur serait revenir au
     * défaut corrigé ici ; l'exclure reviendrait à le filtrer sur un critère
     * qu'on est incapable d'évaluer pour lui.
     */
    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND ua.visible_on_map = true
          AND p.embedding IS NOT NULL
          AND (p.embedding <=> CAST(:queryEmbedding AS vector)) <= :maxDistance
          AND (
              p.location_type IN ('REMOTE', 'ONLINE')
              OR NOT EXISTS (
                  SELECT 1 FROM schedules s
                   WHERE s.program_id = p.id
                     AND s.location IS NOT NULL
              )
              OR EXISTS (
                  SELECT 1 FROM schedules s
                   WHERE s.program_id = p.id
                     AND s.location IS NOT NULL
                     AND ST_DWithin(
                         s.location::geography,
                         ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                         :radiusMeters
                     )
              )
          )
        ORDER BY p.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> semanticSearchInRadius(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("maxDistance") double maxDistance,
        @Param("limit") int limit
    );

    List<Program> findByUserActivityUserIdAndStatusNot(UUID userId, ProgramStatus status);

    List<Program> findByUserActivityId(UUID userActivityId);

    @Query("SELECT COUNT(p) FROM Program p WHERE p.userActivity.user.id = :userId AND p.status = 'ACTIVE'")
    int countActiveByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "UPDATE programs SET embedding = CAST(:embedding AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingVectorString);

    @Query(value = "SELECT * FROM programs WHERE embedding IS NULL", nativeQuery = true)
    List<Program> findByEmbeddingIsNull();

    /**
     * Find programs by organizer (user) ID for GDPR export
     */
    @Query("SELECT p FROM Program p WHERE p.userActivity.user.id = :organisateurId")
    List<Program> findByOrganisateurId(@Param("organisateurId") UUID organisateurId);

    /**
     * Find active, public programs created by a given user (for public profile view).
     */
    @Query("SELECT p FROM Program p WHERE p.userActivity.user.id = :userId AND p.status = 'ACTIVE' AND p.isPublic = true")
    List<Program> findActivePublicByUserId(@Param("userId") UUID userId);

    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND u.location_public = true
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
        )
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> findVisibleInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );

    /**
     * Same visibility filter as findVisibleInRadius, but matches on where the
     * program actually takes place (its nearest schedule location, same source
     * GET /map/activities uses for its markers) instead of the organizer's own
     * profile location. Falls back to the organizer's location only when the
     * program has no schedule with a location.
     */
    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        LEFT JOIN LATERAL (
            SELECT s.location AS loc
            FROM schedules s
            WHERE s.program_id = p.id AND s.location IS NOT NULL
            ORDER BY s.location <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
            LIMIT 1
        ) nearest_schedule ON true
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND ST_DWithin(
              COALESCE(nearest_schedule.loc, CASE WHEN u.location_public THEN u.location END)::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY ST_Distance(
            COALESCE(nearest_schedule.loc, u.location)::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
        )
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> findVisibleNearScheduleOrOrganizer(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );
}
