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
     * Les termes du catalogue que des gens cherchent — et rien d'autre.
     *
     * <p>La requête regroupait la saisie <b>brute</b> de tous les comptes, sans
     * seuil : chercher « Lena Mueller » trois fois faisait apparaître ce nom chez
     * tout le monde, avec un compteur (demande mobile recherche du 15/09).
     *
     * <p>Une saisie n'est retenue que si, ramenée en minuscules, sans accents ni
     * espaces autour, elle est <b>exactement</b> le nom d'une activité ou d'une
     * catégorie : c'est la seule règle qui garantit qu'aucun nom de personne ne
     * sort, là où un filtre de prénoms en laisserait toujours passer. Et elle
     * doit l'avoir été par au moins {@code seuil} personnes distinctes. Le libellé
     * rendu est celui du catalogue, jamais la saisie.
     *
     * <p>Le décompte trie la liste et ne sort pas d'ici. Aucun commentaire SQL
     * dans le corps : une apostrophe y casse l'analyse des paramètres.
     */
    @Query(value = """
        SELECT libelle FROM (
            SELECT libelle, MAX(personnes) AS personnes FROM (
                SELECT a.name AS libelle, COUNT(DISTINCT s.user_id) AS personnes
                FROM search_logs s
                JOIN activities a ON unaccent(LOWER(btrim(s.raw_query))) = unaccent(LOWER(a.name))
                WHERE s.searched_at > :since AND s.user_id IS NOT NULL
                GROUP BY a.name
                UNION ALL
                SELECT c.name, COUNT(DISTINCT s.user_id)
                FROM search_logs s
                JOIN categories c ON unaccent(LOWER(btrim(s.raw_query))) = unaccent(LOWER(c.name))
                WHERE s.searched_at > :since AND s.user_id IS NOT NULL
                GROUP BY c.name
            ) termes
            GROUP BY libelle
        ) retenus
        WHERE personnes >= :seuil
        ORDER BY personnes DESC, libelle
        LIMIT :limit
        """, nativeQuery = true)
    List<String> findPopularCatalogueTerms(@Param("since") Instant since,
                                           @Param("seuil") int seuil,
                                           @Param("limit") int limit);

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
