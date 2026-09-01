package org.program.pair.repository;

import org.program.pair.domain.incident.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /** Un incident « perdu en chemin » existe-t-il déjà pour cette veille ? Pour ne pas le journaliser deux fois. */
    boolean existsByWatchId(UUID watchId);

    /** Mes incidents, du plus récent au plus ancien. */
    java.util.List<Incident> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
