package org.program.pair.domain.map.dto;

public record GeocodingResult(
    double latitude,
    double longitude,
    String address,
    String city,
    String country
) {}
