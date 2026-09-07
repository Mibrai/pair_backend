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
     */
    List<Affiche> findByUserIdOrderByPublishedAtDesc(UUID userId);

    /**
     * Les affiches de quelqu'un dont l'audience figure dans l'ensemble donné.
     *
     * <p>C'est <b>ici</b> que le réglage devient opposable, et pas dans un filtre
     * appliqué après coup : l'ensemble reçu est calculé par le service à partir du
     * lien réel entre les deux personnes, et une audience qui n'y figure pas ne
     * remonte jamais jusqu'au rendu. Passer {@code NOBODY} dans cet ensemble
     * n'aurait aucun sens et n'arrive nulle part — seul le propriétaire lit ses
     * affiches muettes, par la méthode ci-dessus.
     */
    List<Affiche> findByUserIdAndAudienceInOrderByPublishedAtDesc(
        UUID userId, Collection<AfficheAudience> audiences);

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
     */
    @Query(value = """
        SELECT a.user_id, MAX(a.published_at) AS latest
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
        GROUP BY a.user_id
        ORDER BY latest DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findUpdatesSince(
        @Param("viewerId") UUID viewerId,
        @Param("since") Instant since,
        @Param("limit") int limit);
}
