package org.program.pair.domain.affiche;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.affiche.dto.AfficheDto;
import org.program.pair.domain.affiche.dto.AfficheRequests;
import org.program.pair.domain.affiche.dto.AfficheUpdateDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publier une affiche <b>sans être l'hôte</b>, la dépublier, et lire celles
 * qu'on a le droit de voir.
 *
 * <p>La composition — le motif choisi, la phrase, le visuel — reste entièrement
 * côté client : ces routes ne portent que le fait qu'une affiche existe,
 * laquelle, et pour qui.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AfficheController {

    private final AfficheService afficheService;

    @PutMapping("/affiches/{scheduleId}")
    @Operation(summary = "Publier — ou republier — son affiche sur une séance vécue",
        description = "Idempotent : le même appel deux fois laisse une seule affiche. "
            + "L'audience absente vaut NOBODY. Sur un créneau récurrent où l'on est venu "
            + "plusieurs fois, slotStartedAt nomme la séance ; sans lui, c'est la présence "
            + "confirmée la plus récente qui est prise. Le droit de publier vient de la "
            + "PRÉSENCE, jamais de l'organisation.")
    @ApiResponse(responseCode = "403",
        description = "Aucune présence confirmée sur cette séance (AFFICHE_NOT_ATTENDEE)")
    @ApiResponse(responseCode = "404", description = "Créneau introuvable")
    @ApiResponse(responseCode = "422",
        description = "Motif mal formé (AFFICHE_INVALID_MOTIF) ou audience inconnue "
            + "(AFFICHE_INVALID_AUDIENCE)")
    public AfficheDto publish(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody AfficheRequests.PublishRequest request) {
        return afficheService.publish(principal.getId(), scheduleId, request);
    }

    @DeleteMapping("/affiches/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Dépublier son affiche",
        description = "Idempotent : rend 204 même s'il n'y avait rien à retirer. Sans "
            + "slotStartedAt, retire l'affiche de la séance la plus récente sur ce créneau.")
    public void unpublish(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId,
            @Parameter(description = "Début de la séance visée, sur un créneau récurrent.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant slotStartedAt) {
        afficheService.unpublish(principal.getId(), scheduleId, slotStartedAt);
    }

    @GetMapping("/users/{userId}/affiches")
    @Operation(summary = "Les affiches de quelqu'un que j'ai le droit de voir",
        description = "L'audience est appliquée ICI : un lecteur sans droit reçoit un "
            + "tableau vide — jamais 403, qui révélerait ce qu'il n'a pas à savoir. Chez "
            + "soi, tout se voit, y compris les affiches réglées sur NOBODY. Trié de la "
            + "publication la plus récente à la plus ancienne.")
    public List<AfficheDto> forUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        return afficheService.forUser(userId, principal.getId());
    }

    @GetMapping("/affiches/updates")
    @Operation(summary = "Qui a publié depuis — l'anneau sur l'avatar",
        description = "Un identifiant et une date, rien d'autre : ni motif, ni texte, ni "
            + "image. Déjà filtré par l'audience de chacun — un anneau posé sans ce filtre "
            + "révélerait l'existence d'une affiche à qui n'a pas le droit de la voir. "
            + "L'appelant lui-même est exclu. since absent vaut sept jours ; plus ancien "
            + "que trente jours, il est ramené à trente. Un appel au démarrage et au retour "
            + "d'arrière-plan suffit.")
    public List<AfficheUpdateDto> updates(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Borne basse, exclusive, en ISO-8601 UTC.",
                example = "2026-09-01T00:00:00Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return afficheService.updatesSince(principal.getId(), since);
    }
}
