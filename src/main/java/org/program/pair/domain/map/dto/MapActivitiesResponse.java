package org.program.pair.domain.map.dto;

import java.util.List;

/**
 * Response containing all activity markers and the default center point.
 */
public record MapActivitiesResponse(
    List<MapActivityMarkerDto> activities,
    DefaultMapCenter defaultCenter
) {
    public record DefaultMapCenter(
        double lat,
        double lng,
        int zoom
    ) {}
}
