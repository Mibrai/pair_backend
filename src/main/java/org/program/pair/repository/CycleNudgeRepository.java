package org.program.pair.repository;

import org.program.pair.domain.program.CycleNudge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface CycleNudgeRepository extends JpaRepository<CycleNudge, UUID> {

    /**
     * Combien de relances cette personne a reçues depuis {@code since}, toutes
     * étapes et tous programmes confondus.
     *
     * <p>C'est le plafond glissant du contrat : une seule par personne et par
     * 48 h. Il est distinct du plafond par programme, que l'index unique de V102
     * rend structurel.
     */
    long countByUserIdAndSentAtAfter(UUID userId, Instant since);
}
