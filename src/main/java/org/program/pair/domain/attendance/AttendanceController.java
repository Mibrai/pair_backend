package org.program.pair.domain.attendance;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.dto.AttendanceDto;
import org.program.pair.domain.attendance.dto.ConfirmedAttendanceDto;
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

    /**
     * Placée avant la route à gabarit voisine, et lisible seule : {@code /mine}
     * est un littéral d'un seul segment, {@code /{scheduleId}/co-participants}
     * en compte deux — aucun des deux ne peut capter l'autre. Spring préfère de
     * toute façon un chemin littéral à un gabarit, comme {@code /pending} le
     * fait déjà juste au-dessus.
     */
    @GetMapping("/mine")
    @Operation(summary = "Toute son histoire de présences confirmées",
        description = "Une entrée par séance où l'on a confirmé avoir été là, de la plus "
            + "récente à la plus ancienne, sans pagination. Lue dans les présences et JAMAIS "
            + "dans les cartes-souvenirs : une entrée existe pour une séance qui n'a aucune "
            + "carte, là où GET /api/recaps/mine ne rend que les cartes portant au moins une "
            + "contribution. C'est ce qui permet de calculer un motif d'affiche sans le faire "
            + "dépendre de ce qu'un tiers a déposé. Seules les présences confirmées PRÉSENT "
            + "sortent : répondre « je n'y étais pas » n'écrit rien dans cette histoire. "
            + "slotStartedAt porte la séance vécue, la même valeur que sur SlotRecapDto, sur "
            + "AfficheDto et à la publication d'une affiche.")
    public List<ConfirmedAttendanceDto> getMine(@AuthenticationPrincipal UserPrincipal principal) {
        return attendanceService.getMine(principal.getId());
    }

    @GetMapping("/{scheduleId}/co-participants")
    public List<UserPublicDto> getCoParticipants(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return attendanceService.getRecommendableCoParticipants(principal.getId(), scheduleId);
    }
}
