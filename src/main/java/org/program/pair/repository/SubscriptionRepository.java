package org.program.pair.repository;

import org.program.pair.domain.subscription.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
