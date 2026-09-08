package org.program.pair.repository;

import org.program.pair.domain.recap.RecapVibeVote;
import org.program.pair.domain.recap.SlotVibe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RecapVibeVoteRepository extends JpaRepository<RecapVibeVote, UUID> {

    List<RecapVibeVote> findByRecapIdAndUserId(UUID recapId, UUID userId);

    /**
     * Les écritures en attente sont vidangées avant la suppression.
     *
     * <p>Un vote repose souvent les mêmes ambiances qu'il vient de retirer :
     * sans cette vidange, le {@code DELETE} pourrait partir après les
     * {@code INSERT} et la contrainte d'unicité échouerait. Le contexte n'est
     * en revanche pas vidé — la carte chargée juste avant doit rester attachée.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RecapVibeVote v WHERE v.recap.id = :recapId AND v.user.id = :userId")
    void deleteByRecapIdAndUserId(@Param("recapId") UUID recapId, @Param("userId") UUID userId);

    /**
     * Ambiances d'une carte, de la plus choisie à la moins choisie.
     *
     * <p>Le tri secondaire sur le nom n'est pas cosmétique : à égalité de
     * votes, sans lui, deux lectures successives de la même carte peuvent ne
     * pas retenir les trois mêmes ambiances.
     *
     * @return des paires {@code [SlotVibe, Long]}
     */
    @Query("""
        SELECT v.vibe, COUNT(v) FROM RecapVibeVote v
        WHERE v.recap.id = :recapId
        GROUP BY v.vibe
        ORDER BY COUNT(v) DESC, v.vibe ASC
        """)
    List<Object[]> countByVibe(@Param("recapId") UUID recapId);

    /**
     * Les mêmes décomptes, pour <b>plusieurs</b> cartes d'un coup.
     *
     * <p>Ce que remplace cette requête : un appel de {@link #countByVibe} par
     * carte. Sur les 35 cartes de « Mes moments », c'était 35 allers-retours
     * pour une seule colonne — et un aller-retour coûte ~200 ms entre le
     * service européen et la base américaine. Le tri est celui de
     * {@link #countByVibe} au mot près, précédé de la carte : le plafond de
     * trois ambiances se prend ensuite dans l'ordre, et deux lectures
     * successives doivent retenir les trois mêmes.
     *
     * @return des triplets {@code [UUID recapId, SlotVibe, Long]}
     */
    @Query("""
        SELECT v.recap.id, v.vibe, COUNT(v) FROM RecapVibeVote v
        WHERE v.recap.id IN :recapIds
        GROUP BY v.recap.id, v.vibe
        ORDER BY v.recap.id ASC, COUNT(v) DESC, v.vibe ASC
        """)
    List<Object[]> countByVibeForRecaps(@Param("recapIds") Collection<UUID> recapIds);

    /** Ambiances votées par une personne — ce que le client repasse en surbrillance. */
    @Query("SELECT v.vibe FROM RecapVibeVote v WHERE v.recap.id = :recapId AND v.user.id = :userId")
    List<SlotVibe> findVibesByRecapIdAndUserId(@Param("recapId") UUID recapId, @Param("userId") UUID userId);

    /** Les miennes sur plusieurs cartes. @return des paires {@code [UUID recapId, SlotVibe]} */
    @Query("""
        SELECT v.recap.id, v.vibe FROM RecapVibeVote v
        WHERE v.recap.id IN :recapIds AND v.user.id = :userId
        """)
    List<Object[]> findVibesByRecapIdsAndUserId(@Param("recapIds") Collection<UUID> recapIds,
                                                @Param("userId") UUID userId);
}
