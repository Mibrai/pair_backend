package org.program.pair.repository;

import org.program.pair.domain.chat.dto.ProgramMessagingPolicy;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * Ids des programmes visibles autour d'un point, classés par distance.
     *
     * <p>Même filtre de visibilité que {@link #findVisibleInRadius}, mais la
     * distance se mesure <b>là où le programme a lieu</b> — la séance localisée
     * la plus proche, la source dont {@code GET /map/activities} tire déjà ses
     * marqueurs — et non à l'adresse de profil de l'organisateur, qui n'est
     * retenue qu'à défaut de séance localisée.
     *
     * <p>Une requête native ne peut pas porter de {@code JOIN FETCH} : les
     * entités qu'elle rend arrivent avec toutes leurs associations paresseuses,
     * et la construction du DTO les traversait une par programme —
     * {@code userActivity}, puis {@code user}, {@code activity} et
     * {@code category}. Le bornage géographique reste donc en SQL, où seul
     * PostGIS sait le faire, et la reprise passe par
     * {@link #findWithOrganizerDetailsByIds} qui, elle, sait précharger.
     *
     * <p><b>L'ordre rendu ici est le résultat</b>, pas un détail de
     * présentation : c'est le classement par distance au point interrogé. La
     * reprise par {@code IN :ids} ne le conserve pas — aucun {@code IN} ne
     * garantit d'ordre — et il faut le réappliquer depuis cette liste. Le perdre
     * ne casse rien de visible : la page reste complète, seulement mélangée.
     *
     * <p>Aucun commentaire SQL dans le corps de la requête, pour la raison
     * exposée dans {@code ScheduleRepository.findOpenSlotsInRadius}.
     */
    @Query(value = """
        SELECT p.id FROM programs p
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
    List<UUID> findVisibleNearScheduleOrOrganizerIds(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );

    /**
     * Programmes désignés par leurs ids, organisateur et activité déjà chargés.
     *
     * <p>Le DTO de programme traverse systématiquement {@code userActivity} vers
     * l'auteur d'un côté et vers l'activité puis sa catégorie de l'autre. Sans
     * ces {@code JOIN FETCH}, une page de cent programmes paie ces quatre
     * chaînes cent fois.
     *
     * <p><b>Le résultat n'est pas ordonné</b>, et ne peut pas l'être : un
     * {@code IN} rend ce que le plan d'exécution lui donne. L'appelant qui tenait
     * un ordre — celui d'un tri par distance, par exemple — doit le réappliquer
     * depuis sa liste d'ids.
     */
    @Query("""
        SELECT p FROM Program p
        LEFT JOIN FETCH p.userActivity ua
        LEFT JOIN FETCH ua.user
        LEFT JOIN FETCH ua.activity a
        LEFT JOIN FETCH a.category
        WHERE p.id IN :ids
        """)
    List<Program> findWithOrganizerDetailsByIds(@Param("ids") Collection<UUID> ids);

    /**
     * Programmes non archivés d'un auteur, organisateur et activité préchargés.
     *
     * <p>Même préchargement que {@link #findWithOrganizerDetailsByIds}, pour la
     * même raison : c'est une liste, et chaque entrée traverse les mêmes chaînes.
     */
    @Query("""
        SELECT p FROM Program p
        LEFT JOIN FETCH p.userActivity ua
        LEFT JOIN FETCH ua.user
        LEFT JOIN FETCH ua.activity a
        LEFT JOIN FETCH a.category
        WHERE ua.user.id = :userId AND p.status <> :status
        """)
    List<Program> findWithOrganizerDetailsByUserIdAndStatusNot(@Param("userId") UUID userId,
                                                                @Param("status") ProgramStatus status);

    /** Variante préchargée de {@link #findActivePublicByUserId}, pour la même raison. */
    @Query("""
        SELECT p FROM Program p
        LEFT JOIN FETCH p.userActivity ua
        LEFT JOIN FETCH ua.user
        LEFT JOIN FETCH ua.activity a
        LEFT JOIN FETCH a.category
        WHERE ua.user.id = :userId AND p.status = 'ACTIVE' AND p.isPublic = true
        """)
    List<Program> findActivePublicWithOrganizerDetailsByUserId(@Param("userId") UUID userId);

    /**
     * Créneaux de plusieurs programmes en une lecture.
     *
     * <p>Vit ici et non dans {@code ScheduleRepository} parce que c'est un besoin
     * du programme — servir la liste des séances d'une page de programmes — et
     * non une interrogation des créneaux pour eux-mêmes.
     *
     * <p>Le tri par {@code startsAt} reprend celui que {@code Program.schedules}
     * déclare déjà par {@code @OrderBy} : sans lui, l'ordre des séances d'un
     * même programme dépendrait du plan d'exécution du lot, donc du nombre de
     * programmes demandés.
     */
    @Query("SELECT s FROM Schedule s WHERE s.program.id IN :programIds ORDER BY s.startsAt ASC")
    List<org.program.pair.domain.program.Schedule> findSchedulesByProgramIds(
        @Param("programIds") Collection<UUID> programIds);
}
