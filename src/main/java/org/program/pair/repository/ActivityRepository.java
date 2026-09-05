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

    /**
     * Le motif {@code LIKE}, accents ignorés et <b>jokers de l'utilisateur
     * neutralisés</b>.
     *
     * <p>Les {@code replace} imbriqués ne sont pas décoratifs. Les requêtes
     * dérivées de Spring Data ({@code …ContainingIgnoreCase}) échappent
     * {@code %}, {@code _} et {@code \} dans l'argument avant de composer le
     * motif ; une requête native écrite à la main, non. Sans eux, chercher
     * « % » rend <b>tout le référentiel</b> — vérifié : 68 lignes sur 68 — et
     * « cours_ » se met à trouver « course ». L'ordre compte : la barre oblique
     * inverse d'abord, sinon on échapperait les échappements que l'on vient de
     * poser.
     *
     * <p>Écrit une fois et partagé par les quatre requêtes qui en ont besoin
     * (valeur et décompte, avec et sans catégorie) : quatre copies auraient fini
     * par diverger, et le symptôme d'une copie oubliée est un décompte qui ne
     * correspond pas à sa page.
     *
     * <p>Le repli par trigrammes, lui, reçoit la requête <b>brute</b> : il ne
     * compose aucun motif, et les barres obliques ajoutées ici fausseraient la
     * mesure de similarité.
     */
    String UNACCENTED_LIKE_PATTERN =
        "unaccent(LOWER(CONCAT('%', replace(replace(replace(:name, '\\', '\\\\'), "
        + "'%', '\\%'), '_', '\\_'), '%')))";

    /**
     * Recherche par nom, <b>insensible à la casse ET aux accents</b>.
     *
     * <p>Remplace la requête dérivée {@code findByNameContainingIgnoreCase}, qui
     * ne savait ignorer que la casse. Mesuré par le client le 04/09 :
     * {@code course} rend « Course à pied », {@code COURSE} aussi, {@code yog}
     * rend les quatre yogas — mais <b>{@code course a pied} ne rend rien</b>.
     * C'est le cas le plus banal du français, et son coût est précisément ce que
     * ce référentiel existe pour éviter : quelqu'un qui ne voit aucune
     * suggestion crée le cinquième doublon de « Course à pied » au catalogue.
     *
     * <p>{@code unaccent} des deux côtés de la comparaison, comme
     * {@link UserRepository#SEARCH_USERS_BODY} depuis V101. La fonction n'est pas
     * immuable et ne peut donc pas servir dans un index ; cela ne coûte rien ici,
     * un {@code LIKE '%…%'} n'en utilisait déjà aucun.
     *
     * <p>Une requête native, et non du JPQL : {@code unaccent} est une fonction
     * PostgreSQL qu'aucun dialecte Hibernate n'expose.
     */
    @Query(value = "SELECT * FROM activities a WHERE unaccent(LOWER(a.name)) LIKE "
        + UNACCENTED_LIKE_PATTERN,
        countQuery = "SELECT COUNT(*) FROM activities a WHERE unaccent(LOWER(a.name)) LIKE "
        + UNACCENTED_LIKE_PATTERN,
        nativeQuery = true)
    Page<Activity> searchByNameUnaccented(@Param("name") String name, Pageable pageable);

    /** Même recherche, bornée à une catégorie. */
    @Query(value = "SELECT * FROM activities a WHERE a.category_id = :categoryId "
        + "AND unaccent(LOWER(a.name)) LIKE " + UNACCENTED_LIKE_PATTERN,
        countQuery = "SELECT COUNT(*) FROM activities a WHERE a.category_id = :categoryId "
        + "AND unaccent(LOWER(a.name)) LIKE " + UNACCENTED_LIKE_PATTERN,
        nativeQuery = true)
    Page<Activity> searchByCategoryAndNameUnaccented(@Param("categoryId") UUID categoryId,
                                                     @Param("name") String name,
                                                     Pageable pageable);

    /**
     * Rapprochement par trigrammes — la faute de frappe, pas l'accent.
     *
     * <p><b>Une quatrième couche, et elle ne s'exécute qu'en repli</b> : seulement
     * quand la recherche exacte ci-dessus n'a rien rendu. La placer avant ferait
     * remonter des résultats vaguement ressemblants au-dessus de résultats
     * exacts — « Yoga » et « Toga » partagent trois trigrammes sur quatre, et la
     * mesure ne sait pas qu'un seul des deux est un mot. C'est la règle que
     * {@code V77__trigram_search.sql} pose déjà pour la recherche de programmes.
     *
     * <p>{@code corse} rend ainsi « Course à pied », ce que le client relevait
     * comme absent le 04/09. L'index {@code idx_activities_name_trgm} existe
     * depuis V77 ; {@code unaccent} le rend inopérant, ce qui est sans
     * conséquence sur un référentiel de quelques centaines de lignes — le
     * commentaire de V77 le dit déjà.
     *
     * <p><b>{@code word_similarity} et non {@code similarity}</b>, et l'ordre des
     * arguments compte : la première mesure la requête contre le <i>meilleur
     * fragment</i> du nom, la seconde contre le nom entier. Sur « corse » vs
     * « Course à pied », mesuré sur le référentiel : {@code similarity} rend
     * 0,250 — sous n'importe quel seuil utilisable — parce que les deux tiers du
     * nom cible ne sont pas dans la requête. {@code word_similarity} rend 0,444.
     * Le mot cherché est court, la cible ne l'est pas : c'est exactement le cas
     * que {@code similarity} mesure mal.
     *
     * <p>Le seuil de 0,4 est mesuré, pas choisi : sur les sept fautes de frappe
     * d'épreuve — corse, yoag, escallade, natasion, musculatoin, tenis,
     * randonee — la bonne activité sort entre 0,400 et 0,727, quand le plancher
     * de bruit de « corse » est à 0,167. Il est explicite plutôt que laissé à
     * {@code pg_trgm.word_similarity_threshold} : un réglage de session invisible
     * dans le code déciderait de ce que la recherche rend.
     */
    @Query(value = """
        SELECT * FROM activities a
        WHERE word_similarity(unaccent(LOWER(:name)), unaccent(LOWER(a.name))) >= 0.4
        ORDER BY word_similarity(unaccent(LOWER(:name)), unaccent(LOWER(a.name))) DESC, a.name
        """,
        countQuery = """
        SELECT COUNT(*) FROM activities a
        WHERE word_similarity(unaccent(LOWER(:name)), unaccent(LOWER(a.name))) >= 0.4
        """,
        nativeQuery = true)
    Page<Activity> searchByNameSimilar(@Param("name") String name, Pageable pageable);

    /** Même repli, borné à une catégorie. */
    @Query(value = """
        SELECT * FROM activities a
        WHERE a.category_id = :categoryId
          AND word_similarity(unaccent(LOWER(:name)), unaccent(LOWER(a.name))) >= 0.4
        ORDER BY word_similarity(unaccent(LOWER(:name)), unaccent(LOWER(a.name))) DESC, a.name
        """,
        countQuery = """
        SELECT COUNT(*) FROM activities a
        WHERE a.category_id = :categoryId
          AND word_similarity(unaccent(LOWER(:name)), unaccent(LOWER(a.name))) >= 0.4
        """,
        nativeQuery = true)
    Page<Activity> searchByCategoryAndNameSimilar(@Param("categoryId") UUID categoryId,
                                                  @Param("name") String name,
                                                  Pageable pageable);

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
