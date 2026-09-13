package org.program.pair.domain.auth.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /** Révoque toutes les sessions vivantes d'un compte, sauf éventuellement une. */
    @Modifying
    @Query("UPDATE RefreshSession s SET s.revokedAt = :maintenant, s.revokedReason = :motif "
        + "WHERE s.userId = :userId AND s.revokedAt IS NULL "
        + "AND (:epargnee IS NULL OR s.id <> :epargnee)")
    int revoquerToutes(@Param("userId") UUID userId, @Param("epargnee") UUID epargnee,
                       @Param("motif") String motif, @Param("maintenant") Instant maintenant);

    /** Sessions inactives depuis la limite, ou révoquées depuis la limite de révocation. */
    @Modifying
    @Query("DELETE FROM RefreshSession s WHERE s.lastUsedAt < :inactiveAvant "
        + "OR (s.revokedAt IS NOT NULL AND s.revokedAt < :revoqueeAvant)")
    int purger(@Param("inactiveAvant") Instant inactiveAvant, @Param("revoqueeAvant") Instant revoqueeAvant);
}
