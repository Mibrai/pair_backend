package org.program.pair.domain.map.dto;

public record MapCluster(
    Double latitude,
    Double longitude,
    Integer count,
    String type
) {}
