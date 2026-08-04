package org.program.pair.repository;

import org.program.pair.domain.search.SearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * Get popular searches across all users in the last 30 days
     */
    @Query("SELECT s.rawQuery as query, COUNT(s) as searchCount " +
           "FROM SearchLog s " +
           "WHERE s.searchedAt > :since " +
           "GROUP BY s.rawQuery " +
           "ORDER BY COUNT(s) DESC")
    List<Object[]> findPopularSearches(@Param("since") Instant since);

    /**
     * Get user's recent searches (last 10).
     *
     * <p>L'id est projeté pour que le client puisse cibler une entrée précise sur
     * DELETE /api/search/recent/{id} — sans lui, il devait fabriquer une clé par
     * concaténation query+timestamp, instable dès que searchedAt manquait.
     * Le tri secondaire sur l'id rend l'ordre total déterministe quand deux
     * recherches partagent la même milliseconde.
     */
    @Query("SELECT s.id as id, s.rawQuery as query, s.searchedAt as searchedAt " +
           "FROM SearchLog s " +
           "WHERE s.user.id = :userId " +
           "ORDER BY s.searchedAt DESC, s.id DESC")
    List<Object[]> findRecentSearchesByUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Supprime une entrée d'historique, à condition qu'elle appartienne bien à
     * l'appelant. La condition sur userId est dans la requête et non dans un
     * contrôle préalable : une entrée inexistante et une entrée appartenant à
     * quelqu'un d'autre sont indistinguables du dehors (0 ligne affectée dans
     * les deux cas), et ne peuvent donc pas servir à sonder l'existence d'un id.
     *
     * @return le nombre de lignes supprimées : 1 si l'entrée existait et
     *         appartenait à l'utilisateur, 0 sinon.
     */
    @Modifying
    @Query("DELETE FROM SearchLog s WHERE s.id = :id AND s.user.id = :userId")
    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Delete search logs for GDPR purge (Article 17)
     * Search history is considered personal data
     */
    void deleteByUserId(UUID userId);
}
