package org.program.pair.repository;

import org.program.pair.domain.safety.SlotSafetyShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotSafetyShareRepository extends JpaRepository<SlotSafetyShare, UUID> {

    Optional<SlotSafetyShare> findByShareToken(String shareToken);

    boolean existsByShareToken(String shareToken);
}
