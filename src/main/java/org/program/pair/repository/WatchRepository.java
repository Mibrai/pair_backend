package org.program.pair.repository;

import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WatchRepository extends JpaRepository<Watch, UUID> {

    /** Les veilles vivantes de l'appelant, de la plus récente à la plus ancienne. */
    List<Watch> findByUserIdAndStateNotInOrderByArmedAtDesc(UUID userId, Collection<WatchState> terminaux);

    /** Une veille précise de l'appelant : l'appartenance est vérifiée dans la requête. */
    Optional<Watch> findByIdAndUserId(UUID id, UUID userId);

    /** Y a-t-il déjà une veille vivante de cette personne sur ce créneau ? */
    boolean existsByUserIdAndScheduleIdAndStateNotIn(
        UUID userId, UUID scheduleId, Collection<WatchState> terminaux);

    /**
     * Les veilles que les minuteurs doivent examiner : dans l'un des états donnés,
     * et dont l'échéance est passée sans être trop ancienne. La borne haute évite
     * de rebalayer indéfiniment l'historique ; la basse ne prend que ce qui est dû.
     */
    List<Watch> findByStateInAndDeadlineAtBetween(
        Collection<WatchState> states, java.time.Instant depuis, java.time.Instant jusqua);

    boolean existsByPublicToken(String publicToken);
}
