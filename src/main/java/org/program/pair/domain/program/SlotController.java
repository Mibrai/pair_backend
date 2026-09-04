package org.program.pair.domain.program;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.dto.CancelSlotRequest;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.SlotBoundsRequest;
import org.program.pair.domain.program.dto.SlotBoundsResponse;
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
    private final SlotCancellationService slotCancellationService;

    @GetMapping("/feed")
    public List<SlotFeedItemDto> getFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute SlotFeedRequest request) {
        return slotService.getSlotFeed(request, principal.getId());
    }

    /**
     * Les créneaux d'un rectangle — la géométrie d'un écran de carte.
     *
     * <p>Mêmes bornes et même pagination que {@code GET /api/map/bounds}, mêmes
     * filtres que {@code GET /api/slots/feed}. Le client demandait le choix entre
     * une couche {@code slots} ajoutée à {@code /map/bounds} et une route dédiée :
     * c'est la seconde, et le choix est argumenté dans
     * {@code modules/carte/REPONSE_BACKEND_2026-09-04.md}. En un mot,
     * les deux onglets sont deux appels distincts, et les fondre en un ferait
     * payer à chacun le calcul de l'autre — pendant que {@code truncated} et
     * {@code totalInBounds}, aujourd'hui lus par le bandeau de l'onglet Activités,
     * se mettraient à parler aussi des créneaux.
     *
     * <p>{@code /slots/feed} ne change pas : son disque et son plafond de 50 km
     * sont justes pour « autour de moi, à telle distance ».
     *
     * <p><b>Un créneau dont la position n'est pas partagée n'apparaît pas ici</b>,
     * là où le fil le rend sans coordonnées. Répondre « il est dans ce rectangle »
     * est déjà le situer.
     *
     * @return les créneaux, {@code truncated} et {@code totalInBounds}
     */
    @Operation(summary = "Les créneaux d'une zone rectangulaire",
        description = "L'onglet Créneaux de la carte. Contrairement à /slots/feed, aucune "
            + "borne de rayon : la zone interrogée est exactement la zone affichée.")
    @ApiResponse(responseCode = "200", description = "Créneaux de la zone, avec l'état de troncature")
    @ApiResponse(responseCode = "400",
        description = "Rectangle invalide (MAP_BOUNDS_INVALID) ou limit hors bornes (VALIDATION_ERROR)")
    @GetMapping("/bounds")
    public SlotBoundsResponse getSlotsInBounds(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute SlotBoundsRequest request) {
        return slotService.getSlotsInBounds(request, principal.getId());
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

    @PostMapping("/{scheduleId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Annule une séance et prévient tout le monde.",
        description = "Réservé à l'organisateur ; 404 pour quiconque d'autre. Prévient "
            + "immédiatement les inscrits ET la liste d'attente, par notification et par "
            + "e-mail — l'un des rares cas où le double canal se justifie : ne pas "
            + "recevoir une annulation coûte un déplacement pour rien.")
    public void cancel(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody(required = false) CancelSlotRequest request) {
        slotCancellationService.cancel(principal.getId(), scheduleId, request);
    }
}
