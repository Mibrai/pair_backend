package org.program.pair.repository;

import org.program.pair.domain.program.ProgramMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProgramMediaRepository extends JpaRepository<ProgramMedia, UUID> {

    List<ProgramMedia> findByProgramIdOrderBySortOrder(UUID programId);

    /**
     * Médias de plusieurs programmes en une lecture, à répartir par programme
     * chez l'appelant.
     *
     * <p>Le tri est le même que celui de {@link #findByProgramIdOrderBySortOrder}
     * et il porte sur la totalité du lot : {@code sortOrder} n'a de sens qu'au
     * sein d'un programme, mais un tri global suffit à ce que le regroupement
     * par programme conserve l'ordre attendu, un {@code groupingBy} respectant
     * l'ordre du flux.
     */
    @Query("SELECT m FROM ProgramMedia m WHERE m.program.id IN :programIds ORDER BY m.sortOrder ASC")
    List<ProgramMedia> findByProgramIdsOrderBySortOrder(@Param("programIds") Collection<UUID> programIds);
}
