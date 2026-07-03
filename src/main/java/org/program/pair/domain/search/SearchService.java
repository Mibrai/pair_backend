package org.program.pair.domain.search;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.search.dto.TagDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Search tags matching the query.
     * For MVP: returns categories that match the query.
     */
    public List<TagDto> searchTags(String query, int limit) {
        String sql = """
            SELECT
                c.name as tag,
                COUNT(DISTINCT a.id) as count,
                c.name as category
            FROM categories c
            LEFT JOIN activities a ON a.category_id = c.id
            WHERE LOWER(c.name) LIKE LOWER(?)
            GROUP BY c.id, c.name
            ORDER BY count DESC, c.name ASC
            LIMIT ?
        """;

        String searchPattern = "%" + query + "%";

        return jdbcTemplate.query(sql, (rs, rowNum) -> TagDto.builder()
            .tag(rs.getString("tag"))
            .count(rs.getLong("count"))
            .category(rs.getString("category"))
            .build(), searchPattern, limit);
    }

    /**
     * Get popular tags sorted by usage count.
     * For MVP: returns categories sorted by number of activities.
     */
    public List<TagDto> getPopularTags(int limit) {
        String sql = """
            SELECT
                c.name as tag,
                COUNT(DISTINCT a.id) as count,
                c.name as category
            FROM categories c
            LEFT JOIN activities a ON a.category_id = c.id
            GROUP BY c.id, c.name
            ORDER BY count DESC, c.name ASC
            LIMIT ?
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> TagDto.builder()
            .tag(rs.getString("tag"))
            .count(rs.getLong("count"))
            .category(rs.getString("category"))
            .build(), limit);
    }
}
