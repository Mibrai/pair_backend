package org.program.pair.repository;

import org.program.pair.domain.chat.dto.ProgramMessagingPolicy;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    /**
     * Pose le vecteur d'un programme.
     *
     * <p>Même correction, même raison que
     * {@link ActivityRepository#updateEmbedding} : une requête
     * {@code @Modifying} déclarée à la main n'hérite d'aucune transaction, et
     * {@code DemoDataSeeder} l'appelle depuis un {@code CommandLineRunner} qui
     * n'en ouvre pas. La panne y était plus discrète que côté activités — le
     * {@code catch} du seeder la réduit à un {@code log.warn} — mais elle
     * laissait les programmes de démonstration hors de la recherche sémantique.
     *
     * <p>{@code IndexationService.backfillProgramEmbeddings} fournit déjà une
     * transaction ; la propagation par défaut la rejoint et ne change rien pour
     * lui.
     */
    @Transactional
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

    // ------------------------------------------------------------------
    // Relances de cycle (CYCLE_NUDGE). Voir CycleNudgeJob.
    //
    // Les trois requêtes PRÉSÉLECTIONNENT, elles ne décident pas. Chacune est
    // délibérément PLUS LARGE que le prédicat exact, qui est appliqué ensuite en
    // Java par ProgramCycle — même partage que RecurringSlotRolloverJob, où le
    // SQL retient les créneaux commencés et où Java écarte ceux qui ne sont pas
    // terminés.
    //
    // La raison n'est pas le confort : la définition de « terminée » (endsAt
    // déclarée, sinon deux heures) vit dans SlotTiming et NULLE PART AILLEURS. La
    // recopier ici en SQL en ferait une seconde définition, dans un langage où
    // rien ne signalerait sa divergence — exactement ce que SlotTiming a été
    // écrit pour empêcher.
    //
    // Les fenêtres sont bornées des DEUX côtés. La borne haute est le délai du
    // contrat ; la borne basse évite qu'au premier passage le job ne relance
    // tout l'historique d'un coup, et borne le balayage. Même forme que
    // AttendancePromptJob, qui ne regarde que les séances finies depuis une à
    // trois heures.
    // ------------------------------------------------------------------

    /**
     * Étape 2 — le programme attend toujours sa date.
     *
     * <p>Exact, et sans repli Java : le prédicat ne porte que sur une existence
     * et une date de création. Aucun {@code starts_at} n'y entre, donc le piège
     * du « commencé vaut passé » ne s'y pose pas.
     *
     * <p>{@code status <> CANCELLED} et non {@code NOT EXISTS (schedule)} : un
     * programme dont l'unique créneau a été annulé n'a plus de pin sur la carte,
     * et c'est exactement la population cherchée.
     */
    @Query("""
        SELECT p.id FROM Program p
        WHERE p.status = org.program.pair.domain.program.ProgramStatus.ACTIVE
          AND p.createdAt <= :until
          AND p.createdAt > :from
          AND NOT EXISTS (
                SELECT 1 FROM Schedule s
                WHERE s.program = p
                  AND s.status <> org.program.pair.domain.program.SlotStatus.CANCELLED)
          AND NOT EXISTS (
                SELECT 1 FROM CycleNudge n WHERE n.program = p AND n.stage = 2)
        """)
    List<UUID> findStage2Candidates(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Étape 4 — publié, et toujours personne.
     *
     * <p>« Publié » est la création du plus ancien créneau non annulé : le moment
     * où le programme a eu une date, donc un pin, donc une chance d'être rejoint.
     * Ni {@code Program.createdAt}, qui date l'envie, ni
     * {@code subscribersNotifiedAt}, que seul {@code ProgramService.addSchedule}
     * renseigne.
     *
     * <p><b>Personne</b> se lit sur les deux mécanismes d'inscription, et non sur
     * le seul {@code UserProgram} dont {@code enrolledCount} est tiré : quelqu'un
     * peut avoir rejoint un créneau du programme sans s'inscrire au programme, et
     * lui annoncer que personne ne s'est inscrit serait faux.
     *
     * <p>La dernière condition est le pré-filtre : elle affirme seulement qu'une
     * séance commence après 48 h. Que ce soit la <i>prochaine</i>, et qu'aucune
     * ne soit en cours entre-temps, est vérifié en Java —
     * {@code ProgramCycle.nextUnfinishedStart}.
     */
    @Query("""
        SELECT p.id FROM Program p JOIN p.schedules s
        WHERE p.status = org.program.pair.domain.program.ProgramStatus.ACTIVE
          AND s.status <> org.program.pair.domain.program.SlotStatus.CANCELLED
          AND NOT EXISTS (
                SELECT 1 FROM UserProgram up
                WHERE up.program = p
                  AND up.status = org.program.pair.domain.program.UserProgramStatus.ACTIVE)
          AND NOT EXISTS (
                SELECT 1 FROM SlotParticipation sp
                WHERE sp.schedule.program = p
                  AND sp.status IN (
                      org.program.pair.domain.program.ParticipationStatus.INTERESTED,
                      org.program.pair.domain.program.ParticipationStatus.CONFIRMED,
                      org.program.pair.domain.program.ParticipationStatus.WAITLISTED))
          AND NOT EXISTS (
                SELECT 1 FROM CycleNudge n WHERE n.program = p AND n.stage = 4)
          AND EXISTS (
                SELECT 1 FROM Schedule f
                WHERE f.program = p
                  AND f.status <> org.program.pair.domain.program.SlotStatus.CANCELLED
                  AND f.startsAt > :sessionAfter)
        GROUP BY p.id
        HAVING MIN(s.createdAt) <= :until AND MIN(s.createdAt) > :from
        """)
    List<UUID> findStage4Candidates(@Param("from") Instant from,
                                    @Param("until") Instant until,
                                    @Param("sessionAfter") Instant sessionAfter);

    /**
     * Étape 7 — le cycle vient de se refermer.
     *
     * <p>{@code MAX(s.startsAt)} et non l'horizon : l'horizon est toujours
     * postérieur ou égal au dernier début, donc « horizon dépassé depuis 24 h »
     * implique « dernier début dépassé depuis 24 h ». La présélection ne peut
     * donc laisser échapper aucun programme dû, et Java tranche ensuite avec
     * {@code ProgramCycle.closedBy}.
     *
     * <p>La borne basse reçue est déjà élargie de la durée conventionnelle d'une
     * séance par l'appelant, pour la même raison prise dans l'autre sens : un
     * horizon encore dans la fenêtre peut correspondre à un début qui en est
     * sorti.
     */
    @Query("""
        SELECT p.id FROM Program p JOIN p.schedules s
        WHERE p.status = org.program.pair.domain.program.ProgramStatus.ACTIVE
          AND s.status <> org.program.pair.domain.program.SlotStatus.CANCELLED
          AND NOT EXISTS (
                SELECT 1 FROM CycleNudge n WHERE n.program = p AND n.stage = 7)
        GROUP BY p.id
        HAVING MAX(s.startsAt) <= :until AND MAX(s.startsAt) > :from
        """)
    List<UUID> findStage7Candidates(@Param("from") Instant from, @Param("until") Instant until);

    /**
     * Programmes à endormir : actifs, créés avant {@code until}, et toujours
     * sans aucun créneau non annulé.
     *
     * <p>Exacte, et sans repli en Java : le prédicat ne porte que sur une
     * existence et une date de création. Aucun {@code starts_at} n'y entre, donc
     * le piège du « commencé vaut passé » ne s'y pose pas — un programme dont
     * l'unique créneau est passé a eu sa date, il ne dort pas.
     *
     * <p>{@code status <> CANCELLED} et non {@code NOT EXISTS (schedule)} : un
     * programme dont le seul créneau a été annulé n'a plus de pin sur la carte,
     * et c'est exactement la population que le sommeil vise.
     *
     * <p><b>Pas de borne basse ici</b>, contrairement aux trois requêtes de
     * relance. Elles produisent des notifications, et une borne basse est ce qui
     * évite d'en envoyer une salve au premier passage ; celle-ci ne produit qu'un
     * changement d'état, réversible et sans destinataire. Retirer d'un coup les
     * coquilles vides accumulées est précisément ce qu'on lui demande.
     */
    @Query("""
        SELECT p.id FROM Program p
        WHERE p.status = org.program.pair.domain.program.ProgramStatus.ACTIVE
          AND p.createdAt <= :until
          AND NOT EXISTS (
                SELECT 1 FROM Schedule s
                WHERE s.program = p
                  AND s.status <> org.program.pair.domain.program.SlotStatus.CANCELLED)
        """)
    List<UUID> findDormancyCandidates(@Param("until") Instant until);
}
