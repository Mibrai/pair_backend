package org.program.pair.repository;

import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WatchRepository extends JpaRepository<Watch, UUID> {

    /** Les veilles vivantes de l'appelant, de la plus récente à la plus ancienne. */
    List<Watch> findByUserIdAndStateNotInOrderByArmedAtDesc(UUID userId, Collection<WatchState> terminaux);

    /** Les veilles terminées de l'appelant : le journal. */
    List<Watch> findByUserIdAndStateInOrderByArmedAtDesc(UUID userId, Collection<WatchState> etats);

    /**
     * L'issue de chaque veille de l'appelant, de la plus récente à la plus
     * ancienne : deux booléens par veille — a-t-elle été refermée par un code,
     * et a-t-elle mal fini.
     *
     * <p><b>Sur les événements, jamais sur l'état.</b> C'est la condition pour que
     * la série de retours confirmés ne trahisse pas une clôture sous contrainte :
     * celle-ci laisse la veille en {@code ESCALATED}, mais écrit le même
     * {@code CLOSED_BY_CODE} qu'une clôture normale. Compter les états ferait
     * repartir la série à zéro sur cet écran-là, devant la personne qui contraint.
     *
     * <p>Rendu brut plutôt que projeté : la règle qui transforme cette liste en un
     * entier vit dans le service, et elle est trop peu SQL pour y être écrite.
     */
    @Query(value = """
        SELECT w.id,
               EXISTS (SELECT 1 FROM watch_events e
                       WHERE e.watch_id = w.id AND e.type = 'CLOSED_BY_CODE') AS confirme,
               EXISTS (SELECT 1 FROM watch_events e
                       WHERE e.watch_id = w.id
                         AND e.type IN ('ESCALATED', 'ABANDONED', 'LOST_ON_THE_WAY')) AS rompu
        FROM watches w
        WHERE w.user_id = :userId
        ORDER BY w.armed_at DESC
        """, nativeQuery = true)
    List<Object[]> issuesDesVeilles(@Param("userId") UUID userId);

    /** Les veilles d'un créneau dans certains états : les arrivées en attente, pour l'organisateur. */
    List<Watch> findByScheduleIdAndStateIn(UUID scheduleId, Collection<WatchState> etats);

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

    java.util.Optional<Watch> findByPublicToken(String publicToken);

    /**
     * Les veilles de la boucle aller à examiner : pas encore arrivées, dont la base
     * des demandes est passée sans être trop ancienne.
     */
    List<Watch> findByStateInAndOutboundBaseAtBetween(
        Collection<WatchState> states, java.time.Instant depuis, java.time.Instant jusqua);
}
