package org.program.pair.repository;

import org.program.pair.domain.watch.ReturnCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReturnCodeRepository extends JpaRepository<ReturnCode, UUID> {

    /** Le code d'une veille, à la clôture. */
    Optional<ReturnCode> findByWatchId(UUID watchId);

    boolean existsByWatchId(UUID watchId);
}
