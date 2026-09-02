package org.program.pair.repository;

import org.program.pair.domain.watch.Watch;
import org.program.pair.domain.watch.WatchState;
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
public interface WatchRepository extends JpaRepository<Watch, UUID> {

    /** Les veilles vivantes de l'appelant, de la plus récente à la plus ancienne. */
    List<Watch> findByUserIdAndStateNotInOrderByArmedAtDesc(UUID userId, Collection<WatchState> terminaux);

    /**
     * Ce que rend « mes veilles actives » : les veilles vivantes, <b>plus</b> les
     * non-arrivées refermées depuis moins de 24 h.
     *
     * <p><b>Pourquoi une requête à part plutôt qu'un {@code TERMINAUX} assoupli.</b>
     * {@code NOT_ARRIVED} doit rester terminal partout ailleurs : c'est le même
     * ensemble qui autorise {@code existsByUserIdAndScheduleIdAndStateNotIn} à
     * réarmer une veille sur le même créneau. L'en retirer rendrait la non-arrivée
     * bloquante pendant 24 h — précisément le défaut que la terminalité venait de
     * refermer. Le besoin est d'affichage, pas de machine à états : il se sert ici.
     *
     * <p><b>Pourquoi une non-arrivée doit rester visible.</b> Après T+45,
     * l'organisateur reçoit une notification et la personne concernée n'en reçoit
     * aucune. Cette liste est le seul endroit où elle apprend que sa soirée a été
     * classée perdue en chemin et qu'un incident est journalisé à son nom. Le seul
     * effet est cette ligne : la veille est close, aucun geste n'est plus accepté
     * sur elle.
     *
     * <p><b>Conséquence à connaître :</b> c'est le seul cas où cette liste rend une
     * veille dont {@code closedAt} n'est pas nul. Un lecteur qui supposait
     * « active ⟹ non close » se trompera ici.
     */
    @Query("""
        SELECT w FROM Watch w
        WHERE w.userId = :userId
          AND (w.state NOT IN :terminaux
               OR (w.state = :nonArrivee AND w.closedAt > :depuis))
        ORDER BY w.armedAt DESC
        """)
    List<Watch> findActivesEtNonArriveesRecentes(
        @Param("userId") UUID userId,
        @Param("terminaux") Collection<WatchState> terminaux,
        @Param("nonArrivee") WatchState nonArrivee,
        @Param("depuis") Instant depuis);

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
