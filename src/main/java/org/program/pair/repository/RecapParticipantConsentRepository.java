package org.program.pair.repository;

import org.program.pair.domain.recap.RecapParticipantConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecapParticipantConsentRepository
        extends JpaRepository<RecapParticipantConsent, RecapParticipantConsent.Key> {

    Optional<RecapParticipantConsent> findByRecapIdAndUserId(UUID recapId, UUID userId);

    /** Identifiants des seules personnes ayant explicitement accepté d'être nommées. */
    @Query("SELECT c.userId FROM RecapParticipantConsent c "
         + "WHERE c.recapId = :recapId AND c.showIdentity = true")
    List<UUID> findConsentingUserIds(@Param("recapId") UUID recapId);
}
