package org.program.pair.repository;

import org.program.pair.domain.activity.Activity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    Optional<Activity> findBySlug(String slug);

    Page<Activity> findByCategoryId(UUID categoryId, Pageable pageable);

    Page<Activity> findByNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Activity> findByCategoryIdAndNameContainingIgnoreCase(
        UUID categoryId, String name, Pageable pageable);

    @Query("SELECT a FROM Activity a WHERE " +
           "(:categoryId IS NULL OR a.category.id = :categoryId) AND " +
           "(:search IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Activity> searchActivities(
        @Param("categoryId") UUID categoryId,
        @Param("search") String search,
        Pageable pageable
    );

    boolean existsBySlug(String slug);

    boolean existsByCategoryIdAndNameIgnoreCase(UUID categoryId, String name);

    @Query(value = "SELECT * FROM activities WHERE embedding IS NULL", nativeQuery = true)
    List<Activity> findByEmbeddingIsNull();

    @Modifying
    @Query(value = "UPDATE activities SET embedding = CAST(:embedding AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingVectorString);
}
