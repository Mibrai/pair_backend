package org.program.pair.repository;

import org.program.pair.domain.recap.RecapParticipantConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Les mêmes, pour plusieurs cartes d'un coup.
     *
     * <p>La lecture par carte était payée même quand personne n'avait consenti,
     * ce qui est le cas courant : sur les 35 cartes du relevé client, une seule
     * portait un participant nommé — 34 allers-retours pour une liste vide.
     *
     * @return des paires {@code [UUID recapId, UUID userId]}
     */
    @Query("SELECT c.recapId, c.userId FROM RecapParticipantConsent c "
         + "WHERE c.recapId IN :recapIds AND c.showIdentity = true")
    List<Object[]> findConsentingByRecapIds(@Param("recapIds") Collection<UUID> recapIds);
}
