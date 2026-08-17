package org.program.pair.repository;

import org.program.pair.domain.subscription.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
