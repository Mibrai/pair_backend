package org.program.pair.repository;

import org.program.pair.domain.subscription.Subscription;
import org.program.pair.domain.subscription.SubscriptionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findBySubscriberId(UUID subscriberId);

    Optional<Subscription> findByIdAndSubscriberId(UUID id, UUID subscriberId);

    boolean existsBySubscriberIdAndTargetAuthorId(UUID subscriberId, UUID targetAuthorId);

    boolean existsBySubscriberIdAndTargetUserActivityId(UUID subscriberId, UUID targetUserActivityId);

    boolean existsBySubscriberIdAndTargetCategoryId(UUID subscriberId, UUID targetCategoryId);

    Optional<Subscription> findBySubscriberIdAndTargetAuthorId(UUID subscriberId, UUID targetAuthorId);

    Optional<Subscription> findBySubscriberIdAndTargetUserActivityId(UUID subscriberId, UUID targetUserActivityId);

    Optional<Subscription> findBySubscriberIdAndTargetCategoryId(UUID subscriberId, UUID targetCategoryId);

    List<Subscription> findByTargetAuthorId(UUID targetAuthorId);

    List<Subscription> findByTargetUserActivityId(UUID targetUserActivityId);

    List<Subscription> findByTargetCategoryId(UUID targetCategoryId);

    /**
     * Rompt les abonnements qui lient ces deux personnes, dans les deux sens.
     *
     * <p>Effet de bord d'un blocage. Deux types sont concernés et non un seul :
     * l'abonnement à l'auteur, évidemment, mais aussi l'abonnement à l'une de ses
     * activités — « suivre ce que quelqu'un propose, c'est le suivre ». N'en
     * traiter qu'un laisserait le fanout continuer de porter les annonces de
     * quelqu'un qu'on vient de bloquer.
     *
     * <p>Les abonnements par catégorie sont laissés intacts : ils ne visent
     * personne. C'est aussi pourquoi le filtrage des notifications reste
     * nécessaire malgré cette rupture — une catégorie peut annoncer le programme
     * d'un bloqué.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM Subscription s
        WHERE (s.subscriber.id = :a AND s.targetAuthor.id = :b)
           OR (s.subscriber.id = :b AND s.targetAuthor.id = :a)
           OR (s.subscriber.id = :a AND s.targetUserActivity.id IN (
                 SELECT ua.id FROM UserActivity ua WHERE ua.user.id = :b))
           OR (s.subscriber.id = :b AND s.targetUserActivity.id IN (
                 SELECT ua.id FROM UserActivity ua WHERE ua.user.id = :a))
        """)
    int deleteBetween(@Param("a") UUID a, @Param("b") UUID b);

    // — Compteurs d'abonnés —
    //
    // COUNT indexé plutôt que compteur dénormalisé : les index
    // idx_sub_target_* existent depuis la V36 et rendent la mesure assez bon
    // marché pour ne pas justifier un chiffre à maintenir — donc à
    // désynchroniser. Un compteur par type, sans agrégation : additionner celui
    // d'un auteur et ceux de ses activités ne donnerait pas le nombre de
    // personnes touchées par une publication, la déduplication du lot B rendant
    // ce second nombre plus petit.

    long countByTargetAuthorId(UUID targetAuthorId);

    long countByTargetUserActivityId(UUID targetUserActivityId);

    long countByTargetCategoryId(UUID targetCategoryId);

    /**
     * Compteurs d'abonnés d'un lot d'auteurs, en une requête pour toute la page.
     *
     * <p>Rend {@code [targetAuthorId, count]}. Les cibles sans aucun abonné sont
     * absentes du résultat — l'appelant lit 0 par défaut, il ne complète pas une
     * ligne manquante.
     */
    @Query("""
        select s.targetAuthor.id, count(s) from Subscription s
        where s.targetAuthor.id in :ids
        group by s.targetAuthor.id
        """)
    List<Object[]> countByTargetAuthorIds(@Param("ids") Collection<UUID> ids);

    @Query("""
        select s.targetUserActivity.id, count(s) from Subscription s
        where s.targetUserActivity.id in :ids
        group by s.targetUserActivity.id
        """)
    List<Object[]> countByTargetUserActivityIds(@Param("ids") Collection<UUID> ids);

    @Query("""
        select s.targetCategory.id, count(s) from Subscription s
        where s.targetCategory.id in :ids
        group by s.targetCategory.id
        """)
    List<Object[]> countByTargetCategoryIds(@Param("ids") Collection<UUID> ids);

    /**
     * Compteurs de <b>toutes</b> les catégories, en une requête.
     *
     * <p>Le référentiel des catégories est court et {@code GET /api/categories}
     * les rend toutes : borner par une liste d'identifiants coûterait un
     * paramètre sans rien économiser.
     */
    @Query("""
        select s.targetCategory.id, count(s) from Subscription s
        where s.targetCategory is not null
        group by s.targetCategory.id
        """)
    List<Object[]> countAllByTargetCategory();

    // — État d'abonnement de l'appelant —
    //
    // C'est ce qui rend la pagination de /users/me/subscriptions possible : sans
    // ces champs sur les DTO de cible, le client doit charger l'intégralité de
    // ses abonnements pour savoir ce qu'un bouton doit dire.

    @Query("""
        select s.targetAuthor.id from Subscription s
        where s.subscriber.id = :subscriberId and s.targetAuthor.id in :ids
        """)
    List<UUID> findSubscribedAuthorIds(@Param("subscriberId") UUID subscriberId,
                                       @Param("ids") Collection<UUID> ids);

    @Query("""
        select s.targetUserActivity.id from Subscription s
        where s.subscriber.id = :subscriberId and s.targetUserActivity.id in :ids
        """)
    List<UUID> findSubscribedUserActivityIds(@Param("subscriberId") UUID subscriberId,
                                             @Param("ids") Collection<UUID> ids);

    /** Toutes les catégories suivies par l'appelant — voir {@link #countAllByTargetCategory()}. */
    @Query("""
        select s.targetCategory.id from Subscription s
        where s.subscriber.id = :subscriberId and s.targetCategory is not null
        """)
    List<UUID> findSubscribedCategoryIds(@Param("subscriberId") UUID subscriberId);

    // — Listes paginées —
    //
    // Les quatre requêtes ci-dessous rapatrient leurs associations par
    // `left join fetch` plutôt que de les laisser se charger à la demande. Sans
    // cela, rendre une page de vingt lignes déclenche jusqu'à quarante requêtes
    // supplémentaires : `Subscription` porte trois cibles en `LAZY`, et la
    // branche activité se résout en deux sauts (activité-utilisateur, puis
    // activité du référentiel) pour n'obtenir qu'un nom.
    //
    // Le `join fetch` est ici compatible avec la pagination : toutes ces
    // associations sont des `*ToOne`, elles ne multiplient donc pas les lignes
    // et Hibernate pagine en SQL. Ce ne serait pas vrai d'une collection.

    /**
     * Mes abonnements, paginés et triables.
     *
     * <p>Le tri arrive par le {@link org.springframework.data.domain.Pageable} :
     * seul {@code createdAt} est exposé, dans les deux sens. Le tri par nom de
     * cible n'existe pas — il porterait sur trois tables différentes selon le
     * type, et le client y a renoncé plutôt que d'accepter un tri qui ne
     * classerait que la page.
     */
    @Query(value = """
        select s from Subscription s
        left join fetch s.targetAuthor
        left join fetch s.targetUserActivity tua
        left join fetch tua.activity
        left join fetch s.targetCategory
        where s.subscriber.id = :subscriberId
          and (:type is null or s.type = :type)
        """,
        countQuery = """
        select count(s) from Subscription s
        where s.subscriber.id = :subscriberId
          and (:type is null or s.type = :type)
        """)
    Page<Subscription> findMySubscriptions(@Param("subscriberId") UUID subscriberId,
                                           @Param("type") SubscriptionType type,
                                           Pageable pageable);

    /**
     * Mes abonnés : les personnes qui me suivent, moi ou l'une de mes activités.
     *
     * <p>La condition dit exactement ce que « m'appartenir » veut dire : un
     * abonnement {@code AUTHOR} qui me vise, ou un abonnement
     * {@code USER_ACTIVITY} sur une activité dont je suis l'auteur. Rien
     * d'autre ne remonte, et notamment aucun abonnement {@code CATEGORY} — une
     * catégorie n'appartient à personne.
     */
    @Query(value = """
        select s from Subscription s
        join fetch s.subscriber
        left join fetch s.targetUserActivity tua
        left join fetch tua.activity
        where (
                (s.type = org.program.pair.domain.subscription.SubscriptionType.AUTHOR
                    and s.targetAuthor.id = :ownerId)
             or (s.type = org.program.pair.domain.subscription.SubscriptionType.USER_ACTIVITY
                    and tua.user.id = :ownerId)
              )
          and (:type is null or s.type = :type)
          and (:targetId is null or tua.id = :targetId)
        """,
        countQuery = """
        select count(s) from Subscription s
        left join s.targetUserActivity tua
        where (
                (s.type = org.program.pair.domain.subscription.SubscriptionType.AUTHOR
                    and s.targetAuthor.id = :ownerId)
             or (s.type = org.program.pair.domain.subscription.SubscriptionType.USER_ACTIVITY
                    and tua.user.id = :ownerId)
              )
          and (:type is null or s.type = :type)
          and (:targetId is null or tua.id = :targetId)
        """)
    Page<Subscription> findMySubscribers(@Param("ownerId") UUID ownerId,
                                          @Param("type") SubscriptionType type,
                                          @Param("targetId") UUID targetId,
                                          Pageable pageable);
}
