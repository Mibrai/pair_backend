package org.program.pair.domain.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.search.dto.PopularSearchDto;
import org.program.pair.domain.search.dto.RecentSearchDto;
import org.program.pair.repository.SearchLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchHistoryService {

    private final SearchLogRepository searchLogRepository;

    /**
     * Get popular searches across all users in the last 30 days
     *
     * @param limit Maximum number of popular searches to return
     * @return List of popular searches with their counts
     */
    @Transactional(readOnly = true)
    public List<PopularSearchDto> getPopularSearches(int limit) {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

        List<Object[]> results = searchLogRepository.findPopularSearches(thirtyDaysAgo);

        return results.stream()
            .limit(limit)
            .map(row -> new PopularSearchDto(
                (String) row[0],
                (Long) row[1]
            ))
            .toList();
    }

    /**
     * Get user's recent searches (last 10)
     *
     * @param userId User ID
     * @return List of recent searches
     */
    @Transactional(readOnly = true)
    public List<RecentSearchDto> getRecentSearches(UUID userId) {
        List<Object[]> results = searchLogRepository.findRecentSearchesByUser(
            userId, PageRequest.of(0, 10));

        return results.stream()
            .map(row -> new RecentSearchDto(
                (String) row[0],
                (Instant) row[1]
            ))
            .toList();
    }

    /**
     * Clear user's recent searches
     *
     * @param userId User ID
     */
    @Transactional
    public void clearRecentSearches(UUID userId) {
        log.info("Clearing search history for user {}", userId);
        searchLogRepository.deleteByUserId(userId);
    }
}
