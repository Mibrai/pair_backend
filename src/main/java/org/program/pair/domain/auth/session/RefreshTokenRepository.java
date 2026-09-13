package org.program.pair.domain.auth.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Le jeton, verrouillé pour l'échange : deux rafraîchissements simultanés du
     * même jeton se sérialisent au lieu de se croiser.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t JOIN FETCH t.session WHERE t.jti = :jti")
    Optional<RefreshToken> findForUpdate(@Param("jti") UUID jti);

    /**
     * Combien de successeurs de ce jeton ont déjà servi. Au moins un : c'est un
     * rejeu, et non une réponse perdue. Un compte et non un {@code COUNT(t) > 0} :
     * cette forme booléenne rendait faux en présence d'un successeur utilisé.
     */
    @Query("SELECT COUNT(t) FROM RefreshToken t WHERE t.parentJti = :jti AND t.usedAt IS NOT NULL")
    long nombreDeSuccesseursUtilises(@Param("jti") UUID jti);

    @Query("SELECT COUNT(t) FROM RefreshToken t WHERE t.parentJti = :jti")
    long nombreDeSuccesseurs(@Param("jti") UUID jti);

    /**
     * Révoque les frères inutilisés d'un jeton qui sert pour la première fois : ils
     * sont nés de la même réponse perdue, et celui-ci a été reçu.
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :maintenant WHERE t.parentJti = :parent "
        + "AND t.jti <> :jti AND t.usedAt IS NULL AND t.revokedAt IS NULL")
    int revoquerFreres(@Param("parent") UUID parent, @Param("jti") UUID jti, @Param("maintenant") Instant maintenant);

    /** Jetons utilisés depuis la limite dont un successeur a servi : plus rien ne les rendra valables. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.usedAt IS NOT NULL AND t.usedAt < :avant "
        + "AND EXISTS (SELECT 1 FROM RefreshToken f WHERE f.parentJti = t.jti AND f.usedAt IS NOT NULL)")
    int purgerConsommes(@Param("avant") Instant avant);
}
