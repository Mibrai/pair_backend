package org.program.pair.repository;

import org.program.pair.domain.block.UserBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    /**
     * Y a-t-il un blocage entre ces deux personnes, dans un sens ou dans l'autre ?
     *
     * <p>La question posée par toutes les surfaces de lecture. Elle ne dit pas
     * qui a bloqué qui, et c'est voulu : aucune décision de visibilité ne doit
     * dépendre du sens, sans quoi l'un des deux verrait ce que l'autre ne voit
     * pas et le blocage deviendrait détectable.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM UserBlock b
        WHERE (b.blocker.id = :a AND b.blocked.id = :b)
           OR (b.blocker.id = :b AND b.blocked.id = :a)
        """)
    boolean existsBetween(@Param("a") UUID a, @Param("b") UUID b);

    /**
     * Tous ceux qui sont invisibles pour cette personne, les deux sens confondus.
     *
     * <p>Pour les rares surfaces qui filtrent en mémoire faute de pouvoir le
     * faire en SQL. Partout ailleurs, le filtrage descend dans la requête : un
     * post-filtrage rognerait des pages déjà tronquées par un LIMIT et ferait
     * mentir les compteurs.
     */
    @Query("""
        SELECT CASE WHEN b.blocker.id = :userId THEN b.blocked.id ELSE b.blocker.id END
        FROM UserBlock b
        WHERE b.blocker.id = :userId OR b.blocked.id = :userId
        """)
    Set<UUID> findCounterpartIds(@Param("userId") UUID userId);

    @Query("SELECT b FROM UserBlock b WHERE b.blocker.id = :blockerId ORDER BY b.createdAt DESC")
    Page<UserBlock> findByBlockerId(@Param("blockerId") UUID blockerId, Pageable pageable);
}
