package org.program.pair.repository;

import org.program.pair.domain.affiche.Affiche;
import org.program.pair.domain.affiche.AfficheAudience;
import org.program.pair.domain.block.BlockSql;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AfficheRepository extends JpaRepository<Affiche, UUID> {

    /**
     * L'affiche d'une personne pour une séance précise — la clé réelle. Voir
     * {@code uq_affiche_user_occurrence} : sur une série hebdomadaire,
     * {@code (user_id, schedule_id)} seul en désignerait plusieurs.
     */
    Optional<Affiche> findByUserIdAndScheduleIdAndOccurrenceStart(
        UUID userId, UUID scheduleId, Instant occurrenceStart);

    /**
     * Toutes les affiches de quelqu'un — la lecture de son propre profil, seule
     * à voir aussi les {@code NOBODY}.
     *
     * <p><b>{@code LEFT JOIN FETCH} sur toute la chaîne jusqu'à la
     * catégorie</b>, parce que {@code AfficheDto} porte désormais
     * {@code activityName} et {@code categoryColorRamp} : sans lui, le rendu
     * marche {@code Schedule → Program → UserActivity → Activity → Category} à la
     * demande, une fois par niveau.
     *
     * <p>Mesuré au harnais de comptage, sur une galerie de 1 puis de 15
     * affiches : <b>6 requêtes en naïf, 1 avec le fetch</b>. Le coût marginal par
     * affiche est nul dans les deux cas — {@code hibernate.default_batch_fetch_size=32}
     * ({@code application.properties}) résout les quinze créneaux en un seul
     * {@code WHERE id = ANY(?)}, puis les programmes, puis les activités. Ce ne
     * sont donc pas cinq requêtes <i>par affiche</i> mais cinq en tout, et c'est
     * la raison pour laquelle ce fetch a failli être jugé inutile.
     *
     * <p>Il ne l'est pas : la base est à San Francisco et le service en Europe,
     * soit ~200 ms l'aller-retour. <b>Cinq allers-retours fixes valent une
     * seconde</b> sur une galerie, quelle que soit sa taille. Et le plafond de 32
     * n'est garanti par rien : au-delà, le rendu naïf repart par paliers, le
     * fetch non.
     *
     * <p><b>{@code LEFT} et non {@code INNER}, délibérément.</b> Les cinq clés
     * étrangères de la chaîne sont {@code NOT NULL} aujourd'hui, donc les deux
     * écritures rendent les mêmes lignes. Le jour où l'une d'elles deviendrait
     * facultative, une jointure interne ferait <b>disparaître des affiches</b> de
     * la galerie de leur auteur — silencieusement, et sans qu'aucun test du lot
     * ne puisse le voir.
     */
    @Query("""
        SELECT a FROM Affiche a
          LEFT JOIN FETCH a.schedule s
          LEFT JOIN FETCH s.program p
          LEFT JOIN FETCH p.userActivity ua
          LEFT JOIN FETCH ua.activity act
          LEFT JOIN FETCH act.category
        WHERE a.user.id = :userId
        ORDER BY a.publishedAt DESC
        """)
    List<Affiche> findByUserIdOrderByPublishedAtDesc(@Param("userId") UUID userId);

    /**
     * Les affiches de quelqu'un dont l'audience figure dans l'ensemble donné.
     *
     * <p>C'est <b>ici</b> que le réglage devient opposable, et pas dans un filtre
     * appliqué après coup : l'ensemble reçu est calculé par le service à partir du
     * lien réel entre les deux personnes, et une audience qui n'y figure pas ne
     * remonte jamais jusqu'au rendu. Passer {@code NOBODY} dans cet ensemble
     * n'aurait aucun sens et n'arrive nulle part — seul le propriétaire lit ses
     * affiches muettes, par la méthode ci-dessus.
     *
     * <p>Même {@code LEFT JOIN FETCH} que ci-dessus, et pour la même raison : les
     * deux lectures composent le même {@code AfficheDto}, donc paient le même
     * rendu. En doter une seule ferait de l'autre la lente, sans que rien ne le
     * signale — c'est le mode de panne que ce module vient précisément de payer
     * sur {@code /recaps/mine}.
     */
    @Query("""
        SELECT a FROM Affiche a
          LEFT JOIN FETCH a.schedule s
          LEFT JOIN FETCH s.program p
          LEFT JOIN FETCH p.userActivity ua
          LEFT JOIN FETCH ua.activity act
          LEFT JOIN FETCH act.category
        WHERE a.user.id = :userId AND a.audience IN :audiences
        ORDER BY a.publishedAt DESC
        """)
    List<Affiche> findByUserIdAndAudienceInOrderByPublishedAtDesc(
        @Param("userId") UUID userId,
        @Param("audiences") Collection<AfficheAudience> audiences);

    /**
     * La plus récente des affiches de quelqu'un sur ce créneau.
     *
     * <p>Ce que dépublie un {@code DELETE /api/affiches/{scheduleId}} sans
     * séance nommée. Volontairement indépendante des présences, à rebours de la
     * publication : retirer ce qu'on a publié ne doit pas pouvoir échouer parce
     * que la présence sur laquelle l'affiche s'appuyait n'existe plus.
     */
    Optional<Affiche> findFirstByUserIdAndScheduleIdOrderByOccurrenceStartDesc(
        UUID userId, UUID scheduleId);

    /** Les affiches d'une personne, pour l'effacement de compte et les tests. */
    long countByUserId(UUID userId);

    /**
     * Qui a publié depuis {@code since}, parmi ceux dont j'ai le droit de voir
     * les affiches — l'anneau sur l'avatar, en une requête.
     *
     * <p><b>Le filtre d'audience est dans le {@code WHERE}</b>, pas au retour :
     * savoir qu'une personne a publié dépend de <i>son</i> réglage, et un anneau
     * posé sans ce filtre révélerait l'existence d'une affiche à quelqu'un qui n'a
     * pas le droit de la voir. Le signal fuiterait ce que l'affiche protège.
     *
     * <p><b>Le blocage passe par {@link BlockSql}</b> plutôt que par une copie
     * du prédicat : c'est la huitième surface de visibilité du dépôt, et la
     * javadoc de cette classe explique pourquoi la huitième copie est celle qui
     * finit par diverger.
     *
     * <p>La jointure sur {@code subscriptions} est un {@code LEFT JOIN} et non un
     * {@code EXISTS} pour ne coûter qu'un parcours d'index ; le
     * {@code DISTINCT ON} absorbe les doublons qu'elle pourrait produire, comme
     * l'agrégation qu'il remplace le faisait.
     *
     * <p>L'appelant lui-même est exclu : il sait ce qu'il a publié, et un anneau
     * sur son propre avatar n'appelle aucune ouverture.
     *
     * <p><b>{@code display_name} et {@code avatar_url} ne coûtent rien</b>, et
     * c'est mesuré : une requête avant, une requête après. La jointure sur
     * {@code users} était <i>déjà</i> là, pour deux conditions qui n'ont rien à
     * voir avec un nom — {@code u.is_active} et le prédicat de blocage de
     * {@link BlockSql#NOT_BLOCKED_U}, qui désigne {@code u.id}. Les deux colonnes
     * montent dans le {@code SELECT} d'une table de toute façon parcourue.
     *
     * <p><b>{@code DISTINCT ON} et non plus {@code GROUP BY}, parce que la
     * question a changé.</b> Une agrégation rend une <i>valeur</i> :
     * {@code MAX(published_at)} suffisait à baguer un avatar, qui ne demande
     * qu'une date. Une bande d'affiches demande la <b>ligne</b> — le motif,
     * l'activité, la séance — et il n'existe aucune façon honnête de la tirer
     * d'un {@code GROUP BY} : ajouter {@code MAX(motif)} rendrait le motif
     * alphabétiquement dernier, d'une affiche qui n'est pas celle que la date
     * désigne. Un visage annoncerait alors le motif d'une publication et la date
     * d'une autre, et rien ne le signalerait.
     *
     * <p>{@code DISTINCT ON (a.user_id)} avec un {@code ORDER BY (a.user_id,
     * a.published_at DESC, a.id DESC)} garde <b>une ligne entière</b> par
     * personne, la plus récente de celles que ce lecteur a le droit de voir. Le
     * {@code a.id} n'est pas décoratif : deux affiches publiées dans la même
     * milliseconde — republier en ouvrant l'audience sur deux séances — laissent
     * sinon Postgres libre de choisir, et le visage changerait de motif d'un
     * appel à l'autre sans que rien n'ait été publié.
     *
     * <p><b>La sous-requête est nécessaire</b> : {@code DISTINCT ON} impose que
     * son {@code ORDER BY} commence par ses propres expressions, alors que la
     * bande veut l'ordre inverse — la personne qui vient de publier en tête. Les
     * deux tris ne peuvent donc pas tenir dans la même clause, et le
     * {@code LIMIT} doit s'appliquer <b>après</b> la déduplication, sans quoi il
     * couperait dans les affiches au lieu de couper dans les personnes.
     *
     * <p><b>Toujours une requête, cinq jointures de plus comprises.</b>
     * {@code schedules → programs → user_activities → activities → categories}
     * est la chaîne que rend déjà {@code AfficheDto}, écrite ici en SQL parce que
     * cette lecture-là n'a jamais chargé d'entité. L'index
     * {@code idx_affiches_user_published (user_id, published_at DESC)} sert
     * exactement le tri du {@code DISTINCT ON} : aucune migration n'accompagne ce
     * changement.
     */
    @Query(value = """
        SELECT d.user_id, d.latest, d.display_name, d.avatar_url,
               d.motif, d.occurrence_start, d.activity_name, d.color_ramp
        FROM (
          SELECT DISTINCT ON (a.user_id)
                 a.user_id          AS user_id,
                 a.published_at     AS latest,
                 u.display_name     AS display_name,
                 u.avatar_url       AS avatar_url,
                 a.motif            AS motif,
                 a.occurrence_start AS occurrence_start,
                 act.name           AS activity_name,
                 c.color_ramp       AS color_ramp
          FROM affiches a
          JOIN users u ON u.id = a.user_id
          LEFT JOIN subscriptions sub
                 ON sub.subscriber_id = :viewerId
                AND sub.target_author_id = a.user_id
          LEFT JOIN schedules s        ON s.id = a.schedule_id
          LEFT JOIN programs p         ON p.id = s.program_id
          LEFT JOIN user_activities ua ON ua.id = p.user_activity_id
          LEFT JOIN activities act     ON act.id = ua.activity_id
          LEFT JOIN categories c       ON c.id = act.category_id
          WHERE a.published_at > :since
            AND a.user_id <> :viewerId
            AND u.is_active = TRUE
            AND (a.audience = 'EVERYONE'
                 OR (a.audience = 'SUBSCRIBERS' AND sub.id IS NOT NULL))
        """ + BlockSql.NOT_BLOCKED_U + """
          ORDER BY a.user_id, a.published_at DESC, a.id DESC
        ) d
        ORDER BY d.latest DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findUpdatesSince(
        @Param("viewerId") UUID viewerId,
        @Param("since") Instant since,
        @Param("limit") int limit);
}
