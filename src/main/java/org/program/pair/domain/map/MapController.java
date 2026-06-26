package org.program.pair.domain.map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.map.dto.MapSearchRequest;
import org.program.pair.domain.map.dto.MapUserDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
@Validated
public class MapController {

    private final MapService mapService;

    @GetMapping("/users")
    public List<MapUserDto> getUsersOnMap(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute MapSearchRequest request) {
        return mapService.getUsersOnMap(request, principal.getId());
    }
}
