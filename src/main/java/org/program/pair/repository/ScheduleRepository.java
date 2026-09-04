package org.program.pair.repository;

import jakarta.persistence.LockModeType;
import org.program.pair.domain.block.BlockSql;
import org.program.pair.domain.program.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    /**
     * À passer en {@code categoryIds} à {@link #findOpenSlotsInRadius} quand
     * {@code filterByCategory} est faux. Hibernate refuse de lier une liste vide
     * dans un {@code IN} ; il faut donc une liste non vide dont la requête ne fait
     * rien. L'UUID nul n'identifie aucune catégorie.
     */
    Set<UUID> NO_CATEGORY_FILTER = Set.of(new UUID(0L, 0L));

    /**
     * Sentinelle pour le filtre de langue, sur le modèle de
     * {@link #NO_CATEGORY_FILTER} : Hibernate refuse de lier une liste vide dans
     * un {@code IN}, et la requête ne la regarde pas quand le drapeau est faux.
     */
    Collection<String> NO_LANGUAGE_FILTER = List.of("");

    /** Même parade que ci-dessus pour les étiquettes d'accessibilité. */
    Collection<String> NO_TAG_FILTER = List.of("");

    List<Schedule> findByStartsAtBetween(Instant from, Instant to);

    List<Schedule> findByProgramId(UUID programId);

    @Query("SELECT s FROM Schedule s " +
           "LEFT JOIN FETCH s.program p " +
           "LEFT JOIN FETCH p.userActivity ua " +
           "LEFT JOIN FETCH ua.activity a " +
           "LEFT JOIN FETCH a.category " +
           "WHERE s.location IS NOT NULL")
    List<Schedule> findAllWithActivityDetails();

    /**
     * Ids des créneaux localisés à l'intérieur d'un rayon et/ou d'une bbox.
     *
     * <p>Ne renvoie que des ids : la reprise par
     * {@link #findWithActivityDetailsByIds} conserve les {@code LEFT JOIN FETCH}
     * qui évitent le N+1 sur program → userActivity → activity → category. Une
     * requête native renvoyant directement des entités les perdrait, et le
     * bornage se paierait en requêtes supplémentaires — l'inverse du but.
     *
     * <p>Chaque filtre est neutralisé quand ses paramètres sont nuls, ce qui
     * permet de demander un rayon seul, une bbox seule, ou l'intersection des
     * deux. L'opérateur {@code &&} (intersection de bbox) est indexable par le
     * GiST sur {@code location} ; pour un point il équivaut à l'inclusion.
     *
     * <p>Le filtre de catégorie suit la même convention que
     * {@link #findOpenSlotsInRadius} : {@code filterByCategory} dit s'il
     * s'applique, {@code categoryIds} doit être non vide dans tous les cas
     * ({@link #NO_CATEGORY_FILTER}).
     */
    @Query(value = """
        SELECT s.id FROM schedules s
        WHERE s.location IS NOT NULL
          AND (CAST(:radiusMeters AS double precision) IS NULL
               OR ST_DWithin(
                    s.location::geography,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    :radiusMeters))
          AND (CAST(:south AS double precision) IS NULL
               OR s.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326))
          AND (CAST(:filterByCategory AS boolean) = FALSE OR EXISTS (
                SELECT 1 FROM programs p
                JOIN user_activities ua ON p.user_activity_id = ua.id
                JOIN activities a       ON ua.activity_id = a.id
                WHERE p.id = s.program_id AND a.category_id IN (:categoryIds)))
        """, nativeQuery = true)
    List<UUID> findLocatedScheduleIdsWithin(@Param("lat") Double lat,
                                             @Param("lng") Double lng,
                                             @Param("radiusMeters") Integer radiusMeters,
                                             @Param("north") Double north,
                                             @Param("south") Double south,
                                             @Param("east") Double east,
                                             @Param("west") Double west,
                                             @Param("filterByCategory") boolean filterByCategory,
                                             @Param("categoryIds") Collection<UUID> categoryIds);

    @Query("SELECT s FROM Schedule s " +
           "LEFT JOIN FETCH s.program p " +
           "LEFT JOIN FETCH p.userActivity ua " +
           "LEFT JOIN FETCH ua.activity a " +
           "LEFT JOIN FETCH a.category " +
           "WHERE s.location IS NOT NULL AND s.id IN :ids")
    List<Schedule> findWithActivityDetailsByIds(@Param("ids") Collection<UUID> ids);

    /**
     * Verrou pessimiste sur le schedule pendant la vérification de capacité,
     * pour que les deux mécanismes de participation (UserProgram et
     * SlotParticipation) ne puissent jamais dépasser ensemble maxParticipants
     * via une race condition.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.id = :id")
    Optional<Schedule> lockById(@Param("id") UUID id);

    /**
     * Incrémente le compteur d'ouvertures d'une page publique.
     *
     * <p>Un {@code UPDATE} atomique, et non une lecture suivie d'une écriture.
     * Deux ouvertures simultanées du même lien — ce que le partage dans un
     * groupe produit précisément — n'en comptaient qu'une, les deux transactions
     * lisant la même valeur avant que l'une n'écrive.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Schedule s SET s.publicViewCount = s.publicViewCount + 1 "
        + "WHERE s.publicShareToken = :token")
    int incrementPublicViewCount(@Param("token") String token);

    Optional<Schedule> findByPublicShareToken(String publicShareToken);

    boolean existsByPublicShareToken(String publicShareToken);

    /**
     * Nombre de participants confirmés toutes sources confondues : inscriptions
     * actives à un programme structuré (UserProgram) + RSVP confirmés sur le
     * créneau lui-même (SlotParticipation). C'est la vérité unique utilisée
     * pour faire respecter Schedule.maxParticipants quel que soit le chemin
     * d'entrée.
     */
    @Query(value = """
        SELECT
          (SELECT COUNT(*) FROM user_programs up WHERE up.schedule_id = :scheduleId AND up.status = 'ACTIVE')
          +
          (SELECT COUNT(*) FROM slot_participations sp WHERE sp.schedule_id = :scheduleId AND sp.status = 'CONFIRMED')
        """, nativeQuery = true)
    long countConfirmedParticipants(@Param("scheduleId") UUID scheduleId);

    @Query("SELECT s FROM Schedule s WHERE s.program.userActivity.user.id = :userId " +
           "AND s.isOpenToPartners = true ORDER BY s.startsAt DESC")
    List<Schedule> findHostedOpenSlots(@Param("userId") UUID userId);

    @Query("SELECT s FROM Schedule s WHERE s.program.userActivity.user.id = :userId")
    List<Schedule> findHostedSchedules(@Param("userId") UUID userId);

    @Query("SELECT COUNT(s) FROM Schedule s WHERE s.program.userActivity.user.id = :userId")
    long countHostedByUserId(@Param("userId") UUID userId);

    /**
     * Prochaine séance ouverte d'un programme, la plus proche d'abord.
     *
     * <p>C'est ce qui transforme le lecteur d'une carte-souvenir en
     * participant. {@code OPEN} et non {@code OPEN, FULL} : proposer un
     * créneau complet est un cul-de-sac, et il vaut mieux rendre vide et
     * laisser le client proposer l'abonnement au programme.
     */
    @Query("SELECT s FROM Schedule s WHERE s.program.id = :programId "
         + "AND s.status = org.program.pair.domain.program.SlotStatus.OPEN "
         + "AND s.startsAt > :after ORDER BY s.startsAt ASC LIMIT 1")
    Optional<Schedule> findNextOpenSlot(@Param("programId") UUID programId, @Param("after") Instant after);

    @Query("SELECT s FROM Schedule s WHERE s.status IN ('OPEN', 'FULL') " +
           "AND ((s.endsAt IS NOT NULL AND s.endsAt BETWEEN :from AND :to) " +
           "OR (s.endsAt IS NULL AND s.startsAt BETWEEN :fromStart AND :toStart))")
    List<Schedule> findFinishedBetween(@Param("from") Instant from, @Param("to") Instant to,
                                        @Param("fromStart") Instant fromStart, @Param("toStart") Instant toStart);

    @Query("SELECT s FROM Schedule s WHERE s.status IN ('OPEN', 'FULL') AND s.startsAt < :cutoff")
    List<Schedule> findOpenOrFullStartedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Créneaux dont le rappel T-2h est dû : à venir, à moins de {@code horizon},
     * et pas encore rappelés <b>pour cet instant de début</b>.
     *
     * <p>Trois propriétés du rappel tiennent dans ce {@code WHERE}, sans aucune
     * planification par créneau :
     *
     * <ul>
     *   <li>{@code status IN ('OPEN','FULL')} — un créneau annulé sort du
     *       balayage, donc son rappel est annulé sans qu'on ait à l'annuler ;</li>
     *   <li>{@code startsAt > :now} — une séance déjà commencée n'est plus
     *       rappelée, y compris après un arrêt prolongé du service ;</li>
     *   <li>{@code reminderSentFor <> startsAt} — un créneau déplacé redevient
     *       éligible, donc son rappel est replanifié sans qu'on ait à le
     *       replanifier.</li>
     * </ul>
     */
    @Query("SELECT s FROM Schedule s WHERE s.status IN ('OPEN', 'FULL') "
        + "AND s.startsAt > :now AND s.startsAt <= :horizon "
        + "AND (s.reminderSentFor IS NULL OR s.reminderSentFor <> s.startsAt)")
    List<Schedule> findDueForReminder(@Param("now") Instant now, @Param("horizon") Instant horizon);

    /**
     * Un schedule "récurrent" (recurrence_rule non nul, ex. "FREQ=WEEKLY;...")
     * n'a qu'une seule occurrence bookable (starts_at/ends_at) dans ce modèle
     * de données — rien n'expanse automatiquement les occurrences suivantes.
     * Une fois passée, cette unique occurrence est avancée à sa prochaine
     * occurrence réelle par {@code RecurringSlotRolloverJob}, qui lit désormais
     * la RRULE au lieu d'ajouter sept jours en aveugle.
     */
    @Query("SELECT s FROM Schedule s WHERE s.recurrenceRule IS NOT NULL AND s.startsAt < :now")
    List<Schedule> findRecurringStartedBefore(@Param("now") Instant now);

    /**
     * Ce que « les créneaux visibles pour cette personne » veut dire, écrit une
     * fois — sans la géométrie, qui est la seule chose que les deux fils ne
     * partagent pas.
     *
     * <p>Trois requêtes le portent : le fil par rayon ({@link #findOpenSlotsInRadius},
     * {@link #findOpenSlotIdsInRadius}) et la carte par rectangle
     * ({@link #findOpenSlotIdsInBounds}, {@link #countOpenSlotsInBounds}). Un
     * filtre ajouté ici s'applique aux deux géométries : la carte ne peut pas
     * montrer un créneau que le fil cacherait, ni l'inverse.
     *
     * <p>C'est l'argument que {@link BlockSql} porte pour le seul prédicat de
     * blocage, et il vaut a fortiori pour un corps de cette taille : le recopier
     * garantirait qu'une des copies dérive un jour, et la dérive serait
     * silencieuse — une requête qui filtre autrement ne lève rien, elle rend
     * simplement autre chose.
     *
     * <p>Chaque appelant y ajoute ensuite son prédicat géographique, ses
     * restrictions propres s'il en a, {@link BlockSql#NOT_BLOCKED_U}, puis son
     * classement. L'ordre des {@code AND} est indifférent ; leur présence ne
     * l'est pas.
     *
     * <p>La concaténation reste une <i>expression constante</i> au sens du
     * langage, donc utilisable dans une annotation, parce que chaque morceau —
     * celui-ci comme {@link BlockSql#NOT_BLOCKED_U} — est lui-même une constante.
     * Une méthode qui composerait la même chaîne ne compilerait pas ici.
     *
     * <p><b>Aucun commentaire SQL dans ce corps.</b> L'analyseur de paramètres de
     * Spring Data suit les guillemets simples sans savoir qu'il traverse un
     * commentaire : une apostrophe française y casse le chargement du dépôt
     * entier, au démarrage, avec un message qui ne la nomme pas. Les explications
     * vivent donc dans les javadocs.
     */
    String OPEN_SLOTS_VISIBLE_BASE = """
        FROM schedules s
        JOIN programs p         ON s.program_id = p.id
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u            ON ua.user_id = u.id
        WHERE s.is_open_to_partners = TRUE
          AND s.status IN ('OPEN', 'FULL')
          AND s.starts_at BETWEEN :fromTs AND :toTs
          AND p.status = 'ACTIVE'
          AND p.is_public = TRUE
          AND u.is_active = TRUE
          AND ua.visible_on_map = TRUE
          AND (CAST(:activityId AS uuid) IS NULL OR ua.activity_id = :activityId)
          AND (CAST(:filterByCategory AS boolean) = FALSE OR EXISTS (
                SELECT 1 FROM activities a
                WHERE a.id = ua.activity_id AND a.category_id IN (:categoryIds)))
          AND (CAST(:createdSince AS timestamptz) IS NULL OR s.created_at >= :createdSince)
          AND (CAST(:filterByLanguage AS boolean) = FALSE
               OR s.primary_language IS NULL
               OR s.primary_language IN (:languages))
          AND (CAST(:filterByTags AS boolean) = FALSE OR (
                SELECT COUNT(DISTINCT sat.tag) FROM schedule_accessibility_tags sat
                WHERE sat.schedule_id = s.id AND sat.tag IN (:accessibilityTags)
              ) = :requiredTagCount)
        """;

    String OPEN_SLOTS_IN_RADIUS_BODY = OPEN_SLOTS_VISIBLE_BASE + """
          AND ST_DWithin(
                s.location::geography,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters)
        """ + BlockSql.NOT_BLOCKED_U + """
        ORDER BY (s.starts_at AT TIME ZONE :zone)::date ASC,
                 CASE WHEN EXISTS (
                     SELECT 1 FROM user_availability av
                     WHERE av.user_id = :viewerId
                       AND av.day_of_week = EXTRACT(ISODOW FROM (s.starts_at AT TIME ZONE :zone))
                       AND av.time_slot = CASE
                             WHEN EXTRACT(HOUR FROM (s.starts_at AT TIME ZONE :zone)) BETWEEN 6 AND 11
                                  THEN 'MORNING'
                             WHEN EXTRACT(HOUR FROM (s.starts_at AT TIME ZONE :zone)) BETWEEN 12 AND 17
                                  THEN 'AFTERNOON'
                             WHEN EXTRACT(HOUR FROM (s.starts_at AT TIME ZONE :zone)) >= 18
                                  THEN 'EVENING'
                             ELSE 'NIGHT' END
                 ) THEN 0 ELSE 1 END,
                 s.starts_at ASC,
                 ST_Distance(s.location::geography,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
        LIMIT :limit
        """;

    /**
     * Feed "autour de moi" — créneaux ouverts aux partenaires, à venir, dans le
     * rayon demandé. Ne retourne jamais un créneau dont l'hôte est inactif, ni
     * dont l'activité est masquée de la carte (mêmes filtres de visibilité que
     * ProgramRepository.findVisibleNearScheduleOrOrganizerIds).
     *
     * <p><b>Catégories multiples.</b> Le filtre porte sur une liste, pas sur une
     * valeur : la carte laisse cocher plusieurs catégories, et une requête par
     * catégorie fusionnée côté client plafonnerait l'affichage et multiplierait
     * les allers-retours. {@code filterByCategory} porte l'information « y a-t-il
     * un filtre » séparément de la liste, parce qu'Hibernate ne sait pas lier une
     * liste vide dans un {@code IN} — quand il est faux, {@code categoryIds} n'est
     * pas regardé, mais doit tout de même contenir au moins un élément.
     *
     * <p><b>{@code createdSince}</b> répond à « qu'y a-t-il de neuf ? », que la
     * fenêtre {@code fromTs}/{@code toTs} — qui porte sur le <i>début</i> des
     * séances — ne sait pas exprimer. Filtré en base pour ne pas transporter puis
     * jeter les créneaux hors fenêtre.
     *
     * <p><b>Le filtre de langue est permissif, celui d'accessibilité est
     * restrictif</b>, et cette asymétrie est délibérée. Une langue non déclarée
     * veut dire « on ne sait pas », et exclure faute d'information punirait ceux
     * qui n'ont rien rempli. Une étiquette d'accessibilité non déclarée veut dire
     * « rien ne permet de l'affirmer », et montrer quand même le créneau
     * enverrait quelqu'un en fauteuil vers un lieu dont personne n'a garanti
     * l'accueil. Le coût de l'erreur n'est pas du même ordre dans les deux sens.
     *
     * <p>Les étiquettes demandées se <b>cumulent</b> : le compte de celles
     * trouvées doit égaler celui des demandées. Qui filtre « fauteuil » ET
     * « sans alcool » a besoin des deux, pas de l'une ou l'autre.
     *
     * <p><b>Les disponibilités pondèrent, elles n'excluent pas.</b> Le
     * classement se fait par jour d'abord, puis par « ce créneau tombe-t-il dans
     * une case cochée », puis par heure et par distance. Deux conséquences
     * voulues : la chronologie n'est jamais bousculée — un créneau de la semaine
     * prochaine ne passe pas devant un de demain — et la préférence ne joue
     * qu'entre créneaux du même jour. Une personne qui n'a rien coché retrouve
     * exactement l'ordre d'avant, tous les rangs étant alors égaux.
     *
     * <p>Le rapprochement entre un {@code starts_at} en UTC et une case
     * « mardi soir » se fait dans le fuseau applicatif, passé en paramètre. Le
     * seul fuseau que le système connaisse vraiment est celui de l'appareil, qui
     * n'est pas disponible ici et peut différer d'un appareil à l'autre pour la
     * même personne : c'est une approximation, et elle est la même que celle du
     * développement des récurrences.
     *
     * <p><b>Aucun commentaire SQL dans le corps de cette requête.</b>
     * L'analyseur de paramètres de Spring Data suit les guillemets simples sans
     * savoir qu'il traverse un commentaire : une apostrophe française y casse le
     * chargement du dépôt entier, au démarrage, avec un message qui ne la nomme
     * pas. Les explications vivent donc ici.
     */

    @Query(value = "SELECT s.* " + OPEN_SLOTS_IN_RADIUS_BODY, nativeQuery = true)
    List<Schedule> findOpenSlotsInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("fromTs") Instant from,
        @Param("toTs") Instant to,
        @Param("activityId") UUID activityId,
        @Param("filterByCategory") boolean filterByCategory,
        @Param("categoryIds") Collection<UUID> categoryIds,
        @Param("createdSince") Instant createdSince,
        @Param("limit") int limit,
        @Param("viewerId") UUID viewerId,
        @Param("filterByLanguage") boolean filterByLanguage,
        @Param("languages") Collection<String> languages,
        @Param("filterByTags") boolean filterByTags,
        @Param("accessibilityTags") Collection<String> accessibilityTags,
        @Param("requiredTagCount") long requiredTagCount,
        @Param("zone") String zone
    );

    /**
     * Le même fil, réduit aux <b>ids</b> — et c'est la forme que doit appeler
     * toute lecture qui rend ensuite un DTO.
     *
     * <p>Une requête native ne peut pas porter de {@code JOIN FETCH}. Celle qui
     * rend des entités les livre donc avec toutes leurs associations paresseuses,
     * et le mapping qui suit paie ensuite, <b>par créneau</b>, la cascade
     * program → userActivity → activity → category. Le temps de réponse suit alors
     * le nombre d'éléments rendus et non la surface fouillée : mesuré à une
     * seconde fixe plus une seconde et demie par élément, soit sept secondes pour
     * quatre créneaux.
     *
     * <p>La reprise par {@link #findWithActivityDetailsByIds} conserve les
     * {@code LEFT JOIN FETCH} et ramène ces quatre requêtes par élément à une
     * seule pour tout le lot. C'est le patron déjà appliqué à la carte
     * ({@code MapService}), et que {@link #findLocatedScheduleIdsWithin} documente.
     *
     * <p><b>L'ordre est porté par le SQL, pas par la reprise.</b> Le classement —
     * par jour, puis par disponibilité de celui qui regarde, puis par heure et par
     * distance — vit dans le {@code ORDER BY} ci-dessus. {@code findWithActivityDetailsByIds}
     * interroge par {@code IN} et ne garantit aucun ordre. L'appelant doit donc
     * réordonner selon la liste d'ids rendue ici ; s'il ne le fait pas, le tri
     * disparaît sans qu'aucune erreur ne soit levée et sans qu'aucun test ne le
     * voie.
     */
    @Query(value = "SELECT s.id " + OPEN_SLOTS_IN_RADIUS_BODY, nativeQuery = true)
    List<UUID> findOpenSlotIdsInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("fromTs") Instant from,
        @Param("toTs") Instant to,
        @Param("activityId") UUID activityId,
        @Param("filterByCategory") boolean filterByCategory,
        @Param("categoryIds") Collection<UUID> categoryIds,
        @Param("createdSince") Instant createdSince,
        @Param("limit") int limit,
        @Param("viewerId") UUID viewerId,
        @Param("filterByLanguage") boolean filterByLanguage,
        @Param("languages") Collection<String> languages,
        @Param("filterByTags") boolean filterByTags,
        @Param("accessibilityTags") Collection<String> accessibilityTags,
        @Param("requiredTagCount") long requiredTagCount,
        @Param("zone") String zone
    );

    /**
     * Le même fil, dans un <b>rectangle</b> — corps commun de la page et de son
     * compte.
     *
     * <p>Il part de {@link #OPEN_SLOTS_VISIBLE_BASE}, qu'il partage avec le fil :
     * une seule définition de « quels créneaux existent pour cette personne » pour
     * les deux géométries. Un créneau que la carte montrerait et que le fil
     * cacherait — ou l'inverse — serait un défaut, pas une fonctionnalité.
     *
     * <p><b>Ce qu'il ajoute, et pourquoi il l'ajoute seul.</b>
     *
     * <p>1. La géométrie. {@code &&} contre une {@code ST_MakeEnvelope} au lieu
     * d'un {@code ST_DWithin} : c'est tout l'objet du lot. L'opérateur est
     * indexable par le GiST sur {@code location}, et pour un point l'intersection
     * de bbox équivaut à l'inclusion — même idiome que
     * {@link #findLocatedScheduleIdsWithin}.
     *
     * <p>2. <b>La règle de lieu entre dans le {@code WHERE}</b>, et c'est le
     * point le plus important de cette requête. Sur le fil, un créneau dont la
     * position n'est pas partagée remonte quand même, avec {@code lat}/{@code lng}
     * nuls : il est trouvable, il n'est pas situé. Ici la question posée <i>est</i>
     * géographique — « qu'y a-t-il dans ce rectangle ? » — et y répondre pour un
     * créneau à position masquée le situerait dans le rectangle. Zoomée assez
     * près, la réponse <i>est</i> l'adresse. Le prédicat reproduit donc
     * exactement {@code SlotAddressVisibility.resolve} : jamais un lieu
     * {@code ONLINE} ni sans coordonnées, et sinon {@code PUBLIC}, ou
     * {@code show_exact_address}, ou une participation {@code CONFIRMED} de celui
     * qui regarde. Les deux doivent rester d'accord : si l'un des deux change,
     * l'autre doit changer le même jour.
     *
     * <p>3. <b>Ses propres créneaux sont écartés</b>, comme sur le fil — où le
     * filtre est en Java, sur la liste rendue. Ici il est en SQL : un
     * post-filtrage ferait annoncer par {@code totalInBounds} un créneau que la
     * page ne contient pas, et {@code truncated} s'allumerait sur du vide.
     *
     * <p><b>Le classement est chronologique, sans pondération.</b> Le fil range
     * par jour, puis par les disponibilités déclarées de celui qui regarde, puis
     * par heure et par distance. Rien de tout cela n'a de sens ici : il n'y a pas
     * de centre d'où mesurer une distance, et l'ordre n'est pas montré — une
     * carte n'a pas de premier pin. Il ne décide que d'une chose, ce qui survit à
     * {@code limit}, et « les plus proches dans le temps » est la seule réponse
     * qu'on puisse défendre devant quelqu'un qui n'en verra pas la moitié.
     * {@code s.id} en second départage, pour que deux pages successives ne se
     * recouvrent ni ne se manquent.
     *
     * <p><b>Aucun commentaire SQL dans le corps ci-dessous</b> — voir
     * {@link #OPEN_SLOTS_VISIBLE_BASE} : une apostrophe française dans un
     * commentaire casse le chargement du dépôt entier au démarrage.
     */
    String OPEN_SLOTS_IN_BOUNDS_BODY = OPEN_SLOTS_VISIBLE_BASE + """
          AND (CAST(:viewerId AS uuid) IS NULL OR ua.user_id <> :viewerId)
          AND s.location IS NOT NULL
          AND s.place_type <> 'ONLINE'
          AND (s.place_type = 'PUBLIC'
               OR s.show_exact_address = TRUE
               OR EXISTS (
                    SELECT 1 FROM slot_participations sp
                    WHERE sp.schedule_id = s.id
                      AND sp.user_id = :viewerId
                      AND sp.status = 'CONFIRMED'))
          AND s.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
        """ + BlockSql.NOT_BLOCKED_U;

    /**
     * Les ids de la page — bornés, ordonnés, décalés.
     *
     * <p>Des ids et non des entités, pour la raison que documente
     * {@link #findOpenSlotIdsInRadius} : une requête native ne porte pas de
     * {@code JOIN FETCH}, et la reprise par {@link #findWithActivityDetailsByIds}
     * évite le N+1 sur program → userActivity → activity → category.
     *
     * <p><b>L'ordre est porté par le SQL.</b> {@code findWithActivityDetailsByIds}
     * interroge par {@code IN} et ne garantit rien : l'appelant doit réordonner
     * selon la liste rendue ici. S'il ne le fait pas, le tri disparaît sans
     * qu'aucune erreur ne soit levée.
     */
    @Query(value = "SELECT s.id " + OPEN_SLOTS_IN_BOUNDS_BODY + """
        ORDER BY s.starts_at ASC, s.id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<UUID> findOpenSlotIdsInBounds(
        @Param("north") double north,
        @Param("south") double south,
        @Param("east") double east,
        @Param("west") double west,
        @Param("fromTs") Instant from,
        @Param("toTs") Instant to,
        @Param("activityId") UUID activityId,
        @Param("filterByCategory") boolean filterByCategory,
        @Param("categoryIds") Collection<UUID> categoryIds,
        @Param("createdSince") Instant createdSince,
        @Param("viewerId") UUID viewerId,
        @Param("filterByLanguage") boolean filterByLanguage,
        @Param("languages") Collection<String> languages,
        @Param("filterByTags") boolean filterByTags,
        @Param("accessibilityTags") Collection<String> accessibilityTags,
        @Param("requiredTagCount") long requiredTagCount,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    /**
     * Combien de créneaux la zone contient <b>vraiment</b>, avant {@code limit}.
     *
     * <p>Le même corps que la page, à la projection près — c'est la seule façon
     * de garantir que {@code totalInBounds} compte exactement la population que
     * {@code limit} tronque. Un {@code COUNT} qui aurait son propre {@code WHERE}
     * finirait par annoncer des créneaux que la page n'a pas le droit de montrer,
     * et ce total serait alors lui-même une fuite : dire « il y en a trois ici »
     * situe trois créneaux dans le rectangle.
     *
     * <p>Une requête de plus par appel, assumée. La seule alternative — demander
     * {@code limit + 1} lignes et en déduire la troncature — dirait « il y en a
     * plus » sans jamais dire combien, et le client a demandé le compte.
     */
    @Query(value = "SELECT COUNT(*) " + OPEN_SLOTS_IN_BOUNDS_BODY, nativeQuery = true)
    long countOpenSlotsInBounds(
        @Param("north") double north,
        @Param("south") double south,
        @Param("east") double east,
        @Param("west") double west,
        @Param("fromTs") Instant from,
        @Param("toTs") Instant to,
        @Param("activityId") UUID activityId,
        @Param("filterByCategory") boolean filterByCategory,
        @Param("categoryIds") Collection<UUID> categoryIds,
        @Param("createdSince") Instant createdSince,
        @Param("viewerId") UUID viewerId,
        @Param("filterByLanguage") boolean filterByLanguage,
        @Param("languages") Collection<String> languages,
        @Param("filterByTags") boolean filterByTags,
        @Param("accessibilityTags") Collection<String> accessibilityTags,
        @Param("requiredTagCount") long requiredTagCount
    );

    /**
     * Pour chacun des programmes donnés, la <b>séance localisée la plus proche</b>
     * du point interrogé : sa latitude, sa longitude et sa distance en mètres.
     *
     * <p>C'est la coordonnée qu'un résultat de recherche de type {@code program}
     * doit porter. Elle répond à « à quelle distance de moi ? », qui est la
     * question que l'utilisateur lit — là où la prochaine occurrence répondrait
     * à une autre question, et la position de l'organisateur à aucune.
     *
     * <p>Une seule requête pour toute la page de candidats : la résoudre
     * programme par programme ferait deux cents allers-retours sur une recherche.
     * {@code DISTINCT ON} retient la première ligne de chaque groupe, et le tri
     * garantit que c'est la plus proche.
     *
     * <p>Un programme sans aucune séance localisée n'a pas de ligne : l'appelant
     * en tire {@code lat}/{@code lng} nuls, jamais un repli. C'est délibéré —
     * c'est le repli silencieux sur la position de l'organisateur qui a rendu le
     * défaut invisible pendant si longtemps.
     *
     * @return lignes {@code [programId, lat, lng, distanceMeters]}
     */
    @Query(value = """
        SELECT DISTINCT ON (s.program_id)
               s.program_id AS program_id,
               ST_Y(s.location) AS lat,
               ST_X(s.location) AS lng,
               ST_Distance(
                   s.location::geography,
                   ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
               ) AS distance_meters
          FROM schedules s
         WHERE s.program_id IN (:programIds)
           AND s.location IS NOT NULL
         ORDER BY s.program_id, distance_meters
        """, nativeQuery = true)
    List<Object[]> findNearestVenuesByProgramIds(
        @Param("programIds") Collection<UUID> programIds,
        @Param("lat") double lat,
        @Param("lng") double lng
    );
}
