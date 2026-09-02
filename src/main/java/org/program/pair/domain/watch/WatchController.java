package org.program.pair.domain.watch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.watch.dto.ArrivalRequest;
import org.program.pair.domain.watch.dto.ArrivalResponse;
import org.program.pair.domain.watch.dto.CloseErrorResponse;
import org.program.pair.domain.watch.dto.CloseRequest;
import org.program.pair.domain.watch.dto.CreateWatchRequest;
import org.program.pair.domain.watch.dto.InterruptRequest;
import org.program.pair.domain.watch.dto.ResendCodeRequest;
import org.program.pair.domain.watch.dto.WatchDetailDto;
import org.program.pair.domain.watch.dto.WatchDto;
import org.program.pair.domain.watch.dto.WatchHistoryDto;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Les veilles retour de l'appelant : armer, lister les actives, lire l'une avec
 * sa chronologie, désarmer avant départ.
 *
 * <p>Tout est authentifié et sous {@code /api} : une veille est celle d'une
 * personne. Les gestes qui suivent l'armement — arrivée, clôture, minuteurs —
 * arrivent avec les priorités suivantes du lot.
 */
@RestController
@RequestMapping("/api/watches")
@RequiredArgsConstructor
@Tag(name = "Watches", description = "Veilles retour")
@SecurityRequirement(name = "bearerAuth")
public class WatchController {

    private final WatchService watchService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Armer une veille",
        description = "Exige un contact d'urgence accepté. L'échéance vaut par défaut "
            + "la fin du créneau plus une heure.")
    public WatchDto arm(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateWatchRequest request) {
        return watchService.arm(principal.getId(), request);
    }

    @GetMapping("/active")
    @Operation(summary = "Mes veilles actives")
    public List<WatchDto> active(@AuthenticationPrincipal UserPrincipal principal) {
        return watchService.listActive(principal.getId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Une veille et sa chronologie")
    public WatchDetailDto detail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return watchService.detail(principal.getId(), id);
    }

    @GetMapping("/history")
    @Operation(summary = "Mon journal des veilles terminées",
        description = "Les veilles closes, sans aucune coordonnée : horodatage et nom du lieu.")
    public List<WatchHistoryDto> history(@AuthenticationPrincipal UserPrincipal principal) {
        return watchService.history(principal.getId());
    }

    @PostMapping("/{id}/seen-by-host")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "« Je la vois, elle est là » (organisateur)",
        description = "L'organisateur repousse la relance d'arrivée de 15 min. Ne valide pas l'arrivée. "
            + "**États acceptés : ARMED, EN_ROUTE.** Sinon 409 WATCH_NOT_OUTBOUND.")
    public void seenByHost(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        watchService.seenByHost(principal.getId(), id);
    }

    @PostMapping("/{id}/arrival")
    @Operation(summary = "Valider son arrivée sur place",
        description = "Crée le code de retour et le rend en clair, une seule fois. "
            + "**États acceptés : ARMED, EN_ROUTE.** Sinon 409 WATCH_ARRIVAL_NOT_EXPECTED.")
    public ArrivalResponse arrival(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ArrivalRequest request) {
        return watchService.arrival(principal.getId(), id, request);
    }

    /**
     * Refermer une veille par son code.
     *
     * <p><b>{@code 202} sur succès, {@code 409} sur code faux.</b> Le succès rend
     * un corps vide et le même {@code 202} que le code présenté soit le normal ou
     * celui de contrainte — c'est la clause d'indistinguabilité. Le service a déjà
     * validé sa transaction (dont le décrément d'essai sur code faux) quand ce
     * contrôleur lève le {@code 409} : lever depuis le service aurait annulé ce
     * décrément et rendu le plafond de trois essais inopérant.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody CloseRequest request) {
        WatchService.CloseOutcome outcome = watchService.close(principal.getId(), id, request);
        return switch (outcome.status()) {
            case CLOSED -> ResponseEntity.accepted().build();
            // 409 avec un corps qui porte attemptsLeft en clair : l'écran l'annonce
            // avant le dernier essai, sans parser le message traduit. Construit ici
            // plutôt que levé, pour que le corps porte cet entier — le gestionnaire
            // global ne rend que {code, message, timestamp}.
            case WRONG -> ResponseEntity.status(HttpStatus.CONFLICT).body(new CloseErrorResponse(
                ErrorCode.WATCH_CODE_WRONG.name(),
                "Code incorrect. Essais restants : " + outcome.attemptsLeft() + ".",
                outcome.attemptsLeft(), Instant.now()));
            case LOCKED -> ResponseEntity.status(HttpStatus.CONFLICT).body(new CloseErrorResponse(
                ErrorCode.WATCH_CODE_LOCKED.name(),
                "Trop d'essais : ce code ne peut plus être présenté.",
                0, Instant.now()));
        };
    }

    @PostMapping("/{id}/still-coming")
    @Operation(summary = "« Je suis en chemin »",
        description = "Repousse la relance d'arrivée de quinze minutes. "
            + "**États acceptés : ARMED, EN_ROUTE.** Sinon 409 WATCH_NOT_OUTBOUND.")
    public WatchDto stillComing(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return watchService.stillComing(principal.getId(), id);
    }

    @PostMapping("/{id}/abandon")
    @Operation(summary = "« Je n'y vais pas »",
        description = "Désarme sans message et sans compter d'absence. "
            + "**États acceptés : ARMED, EN_ROUTE, et ESCALATED tant que arrivalConfirmedAt est nul.** "
            + "Ce dernier cas est la sortie d'une veille escaladée sans arrivée, qui n'en avait aucune : "
            + "elle se referme en NOT_ARRIVED, et si une alerte était partie, la levée part avec.")
    public WatchDto abandon(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return watchService.abandon(principal.getId(), id);
    }

    @PostMapping("/{id}/snooze")
    @Operation(summary = "Snooze",
        description = "Repousse l'échéance de retour de 30 minutes, sans code, et réarme les rappels. "
            + "**États acceptés : ON_SITE, REMINDING.** Sinon 409 WATCH_NOT_ON_SITE.")
    public WatchDto snooze(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return watchService.snooze(principal.getId(), id);
    }

    @PostMapping("/{id}/panic")
    @Operation(summary = "Panic",
        description = "Fait partir le message d'alerte immédiatement. "
            + "**Exige une arrivée validée** (arrivalConfirmedAt non nul) sur une veille non close. "
            + "Sinon 409 WATCH_NOT_ON_SITE : le geste signale un souci au lieu de l'activité.")
    public WatchDto panic(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return watchService.panic(principal.getId(), id);
    }

    @PostMapping("/{id}/resend-code")
    @Operation(summary = "Renvoyer le code de retour",
        description = "Régénère le code sous mot de passe, une fois par cycle. Le rend en clair, une fois. "
            + "**Exige un code existant**, donc une arrivée validée. Sinon 409 WATCH_NOT_ON_SITE.")
    public ArrivalResponse resendCode(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ResendCodeRequest request) {
        return watchService.resendCode(principal.getId(), id, request);
    }

    @PostMapping("/{id}/interrupt")
    @Operation(summary = "Interrompre la séance",
        description = "On repart plus tôt. Recale l'échéance sur le trajet de retour, ou sur maintenant si déjà rentrée. "
            + "**États acceptés : ON_SITE, REMINDING.** Sinon 409 WATCH_NOT_ON_SITE.")
    public WatchDto interrupt(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody InterruptRequest request) {
        return watchService.interrupt(principal.getId(), id, request);
    }

    @PostMapping("/{id}/revoke-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Révoquer le lien de statut public",
        description = "Éteint la page publique de cette veille, même avant son expiration. **Tous états.**")
    public void revokeLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        watchService.revokePublicLink(principal.getId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Désarmer avant départ",
        description = "Éteint une veille encore armée, sans message et sans compter d'absence. "
            + "**État accepté : ARMED seul.** Sinon 409 WATCH_NOT_DISARMABLE ; après un départ, "
            + "la sortie est /abandon.")
    public void disarm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        watchService.disarm(principal.getId(), id);
    }
}
