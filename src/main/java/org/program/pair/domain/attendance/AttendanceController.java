package org.program.pair.domain.attendance;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.dto.AttendanceDto;
import org.program.pair.domain.attendance.dto.PendingAttendanceDto;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/attendances")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    public record ConfirmAttendanceRequest(@NotNull Boolean wasPresent) {}

    @PostMapping("/{scheduleId}/confirm")
    public AttendanceDto confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @RequestBody ConfirmAttendanceRequest request) {
        return attendanceService.confirm(principal.getId(), scheduleId, request.wasPresent());
    }

    @GetMapping("/pending")
    public List<PendingAttendanceDto> getPending(@AuthenticationPrincipal UserPrincipal principal) {
        return attendanceService.getPending(principal.getId());
    }

    @GetMapping("/{scheduleId}/co-participants")
    public List<UserPublicDto> getCoParticipants(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return attendanceService.getRecommendableCoParticipants(principal.getId(), scheduleId);
    }
}
