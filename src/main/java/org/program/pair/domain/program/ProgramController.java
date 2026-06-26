package org.program.pair.domain.program;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.dto.*;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/programs")
@RequiredArgsConstructor
@Validated
public class ProgramController {

    private final ProgramService programService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramDto createProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateProgramRequest request) {
        return programService.createProgram(principal.getId(), request);
    }

    @GetMapping
    public List<ProgramDto> getMyPrograms(
            @AuthenticationPrincipal UserPrincipal principal) {
        return programService.getMyPrograms(principal.getId());
    }

    @GetMapping("/{programId}")
    public ProgramDto getProgram(
            @PathVariable UUID programId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return programService.getProgram(programId, principal.getId());
    }

    @PutMapping("/{programId}")
    public ProgramDto updateProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody UpdateProgramRequest request) {
        return programService.updateProgram(principal.getId(), programId, request);
    }

    @DeleteMapping("/{programId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) {
        programService.deleteProgram(principal.getId(), programId);
    }

    @PostMapping("/{programId}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto addSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody CreateScheduleRequest request) {
        return programService.addSchedule(principal.getId(), programId, request);
    }

    @DeleteMapping("/{programId}/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @PathVariable UUID scheduleId) {
        programService.deleteSchedule(principal.getId(), scheduleId);
    }
}
