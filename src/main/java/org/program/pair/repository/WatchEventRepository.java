package org.program.pair.repository;

import org.program.pair.domain.watch.WatchEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WatchEventRepository extends JpaRepository<WatchEvent, UUID> {

    /** La chronologie d'une veille, dans l'ordre où les faits se sont produits. */
    List<WatchEvent> findByWatchIdOrderByOccurredAtAsc(UUID watchId);

    /** Ce fait a-t-il déjà été inscrit ? Pour ne pas prévenir deux fois le contact de secours. */
    boolean existsByWatchIdAndType(UUID watchId, org.program.pair.domain.watch.WatchEventType type);

    /** Le fait le plus récent d'une veille : sert à dater « actualisé il y a … » sur la page publique. */
    java.util.Optional<org.program.pair.domain.watch.WatchEvent> findFirstByWatchIdOrderByOccurredAtDesc(UUID watchId);
}
