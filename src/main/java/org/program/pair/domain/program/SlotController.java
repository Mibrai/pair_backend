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

    /**
     * Le fil « autour de moi » — ce à quoi je peux encore me joindre.
     *
     * <p><b>Le fil borne le début</b>, là où {@code /slots/mine?upcoming=true}
     * borne la fin (voir {@link #getMySlots}) : {@code starts_at BETWEEN :from
     * AND :to} dans {@code ScheduleRepository.OPEN_SLOTS_VISIBLE_BASE}. Les deux
     * règles sont justes et ne répondent pas à la même question — « à quoi
     * puis-je me joindre » n'a pas de présent en cours, « mes engagements » en a
     * un. Un créneau commencé disparaît donc du fil et reste dans mes créneaux.
     *
     * <p>Un créneau annulé n'y figure jamais : le prédicat de statut du fil ne
     * retient que {@code OPEN} et {@code FULL}.
     */
    @Operation(summary = "Les créneaux autour de moi",
        description = "Ne montre que ce qui **n'a pas commencé** : la fenêtre from/to "
            + "porte sur le début des séances. Un créneau en cours n'y est plus, alors "
            + "qu'il reste dans GET /api/slots/mine jusqu'à sa fin. Les créneaux annulés "
            + "et terminés en sont absents.")
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
     * <p><b>{@code includePast} — l'interrupteur « Afficher ce qui est terminé ».</b>
     * Ajouté le 05/09 : sans lui, un {@code from} dans le passé paraissait ignoré.
     * Il ne l'était pas — c'est le filtre de statut qui écartait tout, les
     * créneaux terminés passant à {@code PAST} dans l'heure qui suit leur fin.
     * Rien à corriger sur {@code from}, donc, mais un drapeau à ajouter, et une
     * fenêtre plafonnée à trois mois avec lui. Le fil, lui, ne change pas : « à
     * quoi puis-je encore me joindre » n'a pas de passé.
     *
     * @return les créneaux, {@code truncated} et {@code totalInBounds}
     */
    @Operation(summary = "Les créneaux d'une zone rectangulaire",
        description = "L'onglet Créneaux de la carte. Contrairement à /slots/feed, aucune "
            + "borne de rayon : la zone interrogée est exactement la zone affichée. "
            + "includePast=true y fait entrer les séances déjà terminées, sur trois mois "
            + "au plus.")
    @ApiResponse(responseCode = "200", description = "Créneaux de la zone, avec l'état de troncature")
    @ApiResponse(responseCode = "400",
        description = "Rectangle invalide (MAP_BOUNDS_INVALID), limit hors bornes "
            + "(VALIDATION_ERROR), ou from remontant au-delà de la fenêtre de passé "
            + "autorisée (SLOT_PAST_WINDOW_TOO_WIDE)")
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
            + "qui est autorisé. La liste ordonnée des refus est désormais écrite une "
            + "seule fois pour les deux routes (SlotEntryGuard) — blocage, propre "
            + "créneau, ouverture aux partenaires, statut, séance commencée, capacité. "
            + "La frontière du « trop tard » est le **début** de la séance.")
    @ApiResponse(responseCode = "201", description = "Participation enregistrée")
    @ApiResponse(responseCode = "400",
        description = "Créneau n'acceptant plus de participants (SLOT_NOT_ACCEPTING_PARTICIPANTS, "
            + "ce qui couvre annulé et terminé), déjà commencé (SLOT_ALREADY_STARTED), "
            + "fermé aux partenaires (SLOT_NOT_OPEN_TO_PARTNERS), complet (SLOT_FULL), "
            + "son propre créneau (SLOT_OWN_SLOT) ou organisateur bloqué (USER_BLOCKED)")
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

    /**
     * Mes créneaux — ceux que j'héberge et ceux que j'ai rejoints.
     *
     * <p><b>« À venir » se mesure sur la fin, pas sur le début</b>, depuis le
     * commit {@code c0c6cf8} du 05/09/2026. Un créneau commencé reste donc dans
     * cette liste jusqu'à ce qu'il soit réellement terminé : c'est le moment où
     * l'on ouvre l'application pour retrouver l'adresse, et le filtre précédent,
     * porté sur {@code startsAt}, la retirait précisément là (mesuré le 03/09 :
     * créneau commencé depuis 45 minutes absent, créneau à +2 h présent). La
     * convention de fin est celle de {@code SlotTiming} — déclarée, sinon deux
     * heures.
     *
     * <p><b>Deux frontières différentes, et les deux sont justes.</b> Le fil
     * ({@code GET /slots/feed}) et l'inscription ({@code POST
     * /slots/{id}/join}, {@code POST /slots/{id}/waitlist}) ferment au
     * <b>début</b> : on ne se joint pas à une séance commencée. Cette liste-ci
     * ferme à la <b>fin</b> : on a encore besoin de ce à quoi on participe. Une
     * documentation qui n'en décrirait qu'une des deux ferait passer l'autre pour
     * un défaut.
     *
     * <p><b>Un créneau annulé reste dans la liste</b> tant que sa date n'est pas
     * passée — on doit pouvoir ouvrir ce qu'annonce la notification — et se
     * signale par {@code status = CANCELLED}, {@code cancelledAt} et
     * {@code cancellationReason}. Son adresse exacte, en revanche, n'est plus
     * rendue : voir {@code SlotAddressVisibility}.
     */
    @Operation(summary = "Mes créneaux, hébergés et rejoints",
        description = "upcoming=true (défaut) garde ce qui n'est **pas encore terminé** — "
            + "un créneau commencé y figure jusqu'à sa fin, la fin conventionnelle étant "
            + "startsAt + 2 h quand endsAt n'est pas déclarée. C'est le fil et "
            + "l'inscription qui ferment au début, pas cette liste. upcoming=false rend "
            + "aussi le passé. Les créneaux annulés y figurent encore tant que leur date "
            + "n'est pas passée, reconnaissables à status=CANCELLED, cancelledAt et "
            + "cancellationReason ; leur adresse exacte et leurs coordonnées ne sont plus "
            + "rendues.")
    @GetMapping("/mine")
    public List<SlotFeedItemDto> getMySlots(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false, defaultValue = "true") boolean upcoming) {
        return slotService.getMySlots(principal.getId(), upcoming);
    }

    @Operation(summary = "Les inscrits d'un créneau, pour son hôte",
        description = "404 d'abord, quand la fiche du créneau est introuvable pour l'appelant (créneau "
            + "inexistant, hôte bloqué dans un sens ou dans l'autre, compte de l'hôte fermé). Sinon "
            + "réservé à l'hôte (403 SLOT_PARTICIPANTS_HOST_ONLY). Seuls les inscrits "
            + "CONFIRMED, sans personne bloquée avec l'hôte dans un sens ou dans l'autre. Un inscrit "
            + "lit les autres inscrits par GET /api/slots/{scheduleId}/co-participants.")
    @GetMapping("/{scheduleId}/participants")
    public List<SlotParticipantDto> getParticipants(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return slotService.getParticipants(principal.getId(), scheduleId);
    }

    @Operation(summary = "Les autres inscrits d'un créneau, pour un inscrit",
        description = "404 d'abord, quand la fiche du créneau est introuvable pour l'appelant (créneau "
            + "inexistant, hôte bloqué dans un sens ou dans l'autre, compte de l'hôte fermé). Sinon "
            + "réservé aux inscrits CONFIRMED (403 SLOT_PARTICIPANTS_ENROLLED_ONLY). Rend les autres CONFIRMED, l'appelant exclu "
            + "et sans personne bloquée avec lui dans un sens ou dans l'autre : prénom et avatar "
            + "seulement. Canal d'observation à déclarer dans « qui me voit » : les autres inscrits "
            + "de tes créneaux voient ton prénom et ton avatar.")
    @GetMapping("/{scheduleId}/co-participants")
    public List<org.program.pair.domain.program.dto.SlotCoParticipantDto> getCoParticipants(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        return slotService.getCoParticipants(principal.getId(), scheduleId);
    }

    @PostMapping("/{scheduleId}/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Se mettre en liste d'attente.",
        description = "Accepte les créneaux complets — c'est exactement ceux pour "
            + "lesquels cette route existe. Attendre n'est pas s'engager : on peut "
            + "patienter sur plusieurs créneaux qui se chevauchent, et c'est au moment "
            + "de la promotion que le conflit d'agenda est vérifié. **Un créneau qui "
            + "n'est pas complet refuse** (SLOT_NOT_FULL) : il n'y a rien à y attendre, "
            + "et l'app doit appeler POST /join à la place. **Un créneau annulé rend "
            + "404**, comme s'il n'existait plus.")
    @ApiResponse(responseCode = "201", description = "Place en file enregistrée")
    @ApiResponse(responseCode = "400",
        description = "Créneau non complet (SLOT_NOT_FULL), déjà commencé "
            + "(SLOT_ALREADY_STARTED), fermé aux partenaires (SLOT_NOT_OPEN_TO_PARTNERS), "
            + "son propre créneau (SLOT_OWN_SLOT) ou organisateur bloqué (USER_BLOCKED)")
    @ApiResponse(responseCode = "404", description = "Créneau introuvable ou annulé")
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
