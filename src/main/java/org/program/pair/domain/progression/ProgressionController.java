package org.program.pair.domain.progression;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.progression.dto.*;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/progressions")
@RequiredArgsConstructor
public class ProgressionController {

    private final ProgressionService progressionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgressionDto createProgression(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateProgressionRequest request) {
        return progressionService.createProgression(principal.getId(), request);
    }

    @GetMapping("/{id}")
    public ProgressionDto getProgression(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return progressionService.getProgression(id, principal.getId());
    }

    @PutMapping("/{id}")
    public ProgressionDto updateProgression(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProgressionRequest request) {
        return progressionService.updateProgression(id, principal.getId(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProgression(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        progressionService.deleteProgression(id, principal.getId());
    }

    @GetMapping("/program/{programId}")
    public Page<ProgressionDto> getProgressionsByProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return progressionService.getProgressionsByProgram(programId, principal.getId(), page, size);
    }

    @GetMapping("/user/{userId}")
    public Page<ProgressionDto> getProgressionsByUser(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return progressionService.getProgressionsByUser(userId, page, size);
    }

    @GetMapping("/my")
    public Page<ProgressionDto> getMyProgressions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return progressionService.getProgressionsByUser(principal.getId(), page, size);
    }

    @GetMapping("/my/streak")
    public StreakDto getMyStreak(@AuthenticationPrincipal UserPrincipal principal) {
        return progressionService.calculateStreak(principal.getId());
    }

    @GetMapping("/my/stats")
    public ProgressionStatsDto getMyStats(@AuthenticationPrincipal UserPrincipal principal) {
        return progressionService.getProgressionStats(principal.getId());
    }
}
