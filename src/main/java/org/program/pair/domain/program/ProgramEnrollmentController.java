package org.program.pair.domain.program;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.dto.*;
import org.program.pair.shared.dto.ScheduleConflictResponse;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@Tag(name = "Program Enrollment", description = "Program enrollment and participant activity management")
@SecurityRequirement(name = "bearerAuth")
public class ProgramEnrollmentController {

    private final ProgramEnrollmentService enrollmentService;

    @PostMapping("/programs/{programId}/join")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Join a program",
        description = "Enroll the authenticated user in a program with an optional schedule. "
            + "Refusé si l'un des créneaux visés chevauche un engagement déjà pris : la règle "
            + "de non-chevauchement est appliquée ici, et pas seulement affichée par le client. "
            + "Sans scheduleId, ce sont tous les créneaux du programme qui sont confrontés à "
            + "l'agenda."
    )
    @ApiResponse(responseCode = "201", description = "Inscription enregistrée")
    @ApiResponse(responseCode = "409", description = "Chevauchement d'agenda (SCHEDULE_CONFLICT)",
        content = @Content(schema = @Schema(implementation = ScheduleConflictResponse.class)))
    public UserProgramDto joinProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the program to join") @PathVariable UUID programId,
            @Valid @RequestBody(required = false) JoinProgramRequest request) {
        UUID scheduleId = request != null ? request.scheduleId() : null;
        return enrollmentService.joinProgram(principal.getId(), programId, scheduleId);
    }

    @PostMapping("/programs/{programId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Leave a program",
        description = "Unenroll from a program with an optional reason"
    )
    public void leaveProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the program to leave") @PathVariable UUID programId,
            @Valid @RequestBody(required = false) LeaveProgramRequest request) {
        UUID userProgramId = request != null ? request.userProgramId() : null;
        String reason = request != null ? request.reason() : null;

        if (userProgramId != null) {
            enrollmentService.leaveProgram(principal.getId(), userProgramId, reason);
        } else {
            throw new org.program.pair.shared.exception.ValidationException(
                "userProgramId is required in request body"
            );
        }
    }

    @GetMapping("/users/me/programs")
    @Operation(
        summary = "Get my enrolled programs",
        description = "Retrieve all programs the authenticated user is enrolled in, optionally filtered by status"
    )
    public List<UserProgramDto> getMyEnrolledPrograms(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Filter by enrollment status (ACTIVE, COMPLETED, LEFT, PAUSED)")
            @RequestParam(required = false) UserProgramStatus status) {
        return enrollmentService.getMyPrograms(principal.getId(), status);
    }

    @PatchMapping("/users/me/programs/{userProgramId}")
    @Operation(
        summary = "Update enrollment progress",
        description = "Update the progress percentage for a program enrollment (0-100)"
    )
    public UserProgramDto updateProgress(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the enrollment to update") @PathVariable UUID userProgramId,
            @Valid @RequestBody UpdateProgressRequest request) {
        Integer progressPercentage = request.progressPercentage();
        if (progressPercentage == null) {
            throw new org.program.pair.shared.exception.ValidationException(
                "progressPercentage is required"
            );
        }
        return enrollmentService.updateProgress(principal.getId(), userProgramId, progressPercentage);
    }

    @DeleteMapping("/users/me/programs/{userProgramId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Unenroll from a program",
        description = "Remove enrollment from a program (same as leave)"
    )
    public void unenroll(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the enrollment to remove") @PathVariable UUID userProgramId) {
        enrollmentService.leaveProgram(principal.getId(), userProgramId, null);
    }

    @PostMapping("/users/me/programs/{userProgramId}/activities/{activityId}/complete")
    @Operation(
        summary = "Mark activity as completed",
        description = "Mark a specific activity within a program enrollment as completed"
    )
    public UserProgramDto completeActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the program enrollment") @PathVariable UUID userProgramId,
            @Parameter(description = "ID of the activity to complete") @PathVariable UUID activityId) {
        return enrollmentService.completeActivity(principal.getId(), userProgramId, activityId);
    }

    @PostMapping("/users/me/programs/{userProgramId}/activities/{activityId}/skip")
    @Operation(
        summary = "Skip an activity",
        description = "Mark a specific activity within a program enrollment as skipped"
    )
    public UserProgramDto skipActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the program enrollment") @PathVariable UUID userProgramId,
            @Parameter(description = "ID of the activity to skip") @PathVariable UUID activityId) {
        return enrollmentService.skipActivity(principal.getId(), userProgramId, activityId);
    }

    @GetMapping("/programs/{programId}/participants/count")
    @Operation(
        summary = "Get participant count",
        description = "Get the number of active participants in a program"
    )
    public ParticipantCountResponse getParticipantCount(
            @Parameter(description = "ID of the program") @PathVariable UUID programId) {
        long count = enrollmentService.getActiveParticipantCount(programId);
        return new ParticipantCountResponse(count);
    }

    @GetMapping("/programs/{programId}/enrollment-status")
    @Operation(
        summary = "Check enrollment status",
        description = "Check if the authenticated user is enrolled in a program"
    )
    public EnrollmentStatusResponse getEnrollmentStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "ID of the program") @PathVariable UUID programId) {
        boolean isEnrolled = enrollmentService.isUserEnrolled(principal.getId(), programId);
        return new EnrollmentStatusResponse(isEnrolled);
    }

    // Response DTOs for simple responses
    public record ParticipantCountResponse(long count) {}
    public record EnrollmentStatusResponse(boolean enrolled) {}
}
