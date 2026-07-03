package org.program.pair.domain.map.dto;

import java.util.List;

public record MapMarkersResponse(
    List<MapUserDto> users,
    List<MapActivityDto> activities,
    List<MapProgramDto> programs
) {}
