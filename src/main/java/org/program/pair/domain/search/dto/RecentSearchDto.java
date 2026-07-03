package org.program.pair.domain.search.dto;

import java.time.Instant;

public record RecentSearchDto(
    String query,
    Instant searchedAt
) {}
