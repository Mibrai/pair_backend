package org.program.pair.domain.search.dto;

public record PopularSearchDto(
    String query,
    Long searchCount
) {}
