package org.program.pair.repository;

import org.program.pair.domain.search.SearchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SearchLogRepository extends JpaRepository<SearchLog, UUID> {

    List<SearchLog> findByUserIdOrderBySearchedAtDesc(UUID userId);

    @Query("SELECT s FROM SearchLog s WHERE s.user.id = :userId AND s.searchedAt > :since ORDER BY s.searchedAt DESC")
    List<SearchLog> findRecentByUser(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query("SELECT COUNT(s) FROM SearchLog s WHERE s.searchedAt > :since")
    long countSearchesSince(@Param("since") Instant since);

    /**
     * Delete search logs for GDPR purge (Article 17)
     * Search history is considered personal data
     */
    void deleteByUserId(UUID userId);
}
