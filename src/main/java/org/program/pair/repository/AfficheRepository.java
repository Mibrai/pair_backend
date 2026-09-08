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
     * {@code EXISTS} pour ne coûter qu'un parcours d'index ; l'agrégation absorbe
     * les doublons qu'elle pourrait produire.
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
     * <p><b>Elles entrent aussi dans le {@code GROUP BY}, et ce n'est pas une
     * maladresse à corriger.</b> {@code GROUP BY a.user_id} seul ne suffit pas à
     * Postgres pour laisser sortir {@code u.display_name} : la dépendance
     * fonctionnelle qu'il reconnaît porte sur {@code u.id}, la clé primaire de la
     * table jointe, et non sur {@code a.user_id} — même si la jointure rend les
     * deux égales. Le coût est nul, la clé de regroupement étant déjà
     * l'identifiant de la personne.
     */
    @Query(value = """
        SELECT a.user_id, MAX(a.published_at) AS latest, u.display_name, u.avatar_url
        FROM affiches a
        JOIN users u ON u.id = a.user_id
        LEFT JOIN subscriptions sub
               ON sub.subscriber_id = :viewerId
              AND sub.target_author_id = a.user_id
        WHERE a.published_at > :since
          AND a.user_id <> :viewerId
          AND u.is_active = TRUE
          AND (a.audience = 'EVERYONE'
               OR (a.audience = 'SUBSCRIBERS' AND sub.id IS NOT NULL))
        """ + BlockSql.NOT_BLOCKED_U + """
        GROUP BY a.user_id, u.display_name, u.avatar_url
        ORDER BY latest DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findUpdatesSince(
        @Param("viewerId") UUID viewerId,
        @Param("since") Instant since,
        @Param("limit") int limit);
}
