package org.program.pair.domain.program;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.program.dto.SlotFeedRequest;
import org.program.pair.domain.program.dto.SlotParticipantDto;
import org.program.pair.shared.dto.ScheduleConflictResponse;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
@Validated
public class SlotController {

    private final SlotService slotService;

    @GetMapping("/feed")
    public List<SlotFeedItemDto> getFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute SlotFeedRequest request) {
        return slotService.getSlotFeed(request, principal.getId());
    }

    @GetMapping("/{scheduleId}")
    public SlotFeedItemDto getSlot(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return slotService.getSlot(scheduleId, principal.getId());
    }

    @PostMapping("/{scheduleId}/join")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Rejoindre un créneau ouvert",
        description = "Même règle de non-chevauchement et même enveloppe de refus que "
            + "POST /api/programs/{programId}/join : le chemin d'entrée ne change pas ce "
            + "qui est autorisé.")
    @ApiResponse(responseCode = "201", description = "Participation enregistrée")
    @ApiResponse(responseCode = "409", description = "Chevauchement d'agenda (SCHEDULE_CONFLICT)",
        content = @Content(schema = @Schema(implementation = ScheduleConflictResponse.class)))
    public SlotFeedItemDto join(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody(required = false) JoinSlotRequest request) {
        return slotService.joinSlot(principal.getId(), scheduleId,
            request != null ? request : new JoinSlotRequest(null));
    }

    @DeleteMapping("/{scheduleId}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        slotService.leaveSlot(principal.getId(), scheduleId);
    }

    @GetMapping("/mine")
    public List<SlotFeedItemDto> getMySlots(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false, defaultValue = "true") boolean upcoming) {
        return slotService.getMySlots(principal.getId(), upcoming);
    }

    @GetMapping("/{scheduleId}/participants")
    public List<SlotParticipantDto> getParticipants(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return slotService.getParticipants(principal.getId(), scheduleId);
    }

    @PostMapping("/{scheduleId}/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Se mettre en liste d'attente.",
        description = "Accepte les créneaux complets — c'est exactement ceux pour "
            + "lesquels cette route existe. Attendre n'est pas s'engager : on peut "
            + "patienter sur plusieurs créneaux qui se chevauchent, et c'est au moment "
            + "de la promotion que le conflit d'agenda est vérifié.")
    public SlotFeedItemDto joinWaitlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return slotService.joinWaitlist(principal.getId(), scheduleId);
    }

    @DeleteMapping("/{scheduleId}/waitlist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quitter la liste d'attente. Les rangs suivants remontent.")
    public void leaveWaitlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        slotService.leaveWaitlist(principal.getId(), scheduleId);
    }

    @GetMapping("/{scheduleId}/waitlist")
    @Operation(summary = "La liste d'attente, réservée à l'organisateur.",
        description = "404 pour quiconque d'autre, jamais 403 : un refus nommé "
            + "confirmerait l'existence du créneau.")
    public List<SlotParticipantDto> getWaitlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return slotService.getWaitlist(principal.getId(), scheduleId);
    }
}
