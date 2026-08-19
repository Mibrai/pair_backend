package org.program.pair.domain.calendar;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAddressVisibility;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.publicslot.PublicSlotService;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Les trois façons d'emporter un créneau dans son agenda.
 *
 * <p>Elles se distinguent par une seule chose : <b>qui télécharge</b>, et donc
 * quelle adresse a le droit de partir avec le fichier. Un participant confirmé
 * emporte l'adresse exacte — il la voit déjà dans l'application, et un agenda
 * qui ne dit pas où aller ne sert à rien. La route publique, elle, n'a pas de
 * demandeur : elle ne peut donc emporter que ce qui est diffusable sans savoir
 * qui regarde.
 *
 * <p>C'est la distinction que {@code SlotAddressVisibility} porte déjà, entre
 * {@code resolve} — qui a besoin de connaître l'appelant — et
 * {@code broadcastableAddress}, qui n'en a pas besoin et ne peut donc
 * qu'être plus restrictive.
 */
@Controller
@RequiredArgsConstructor
public class SlotCalendarController {

    private final SlotCalendarService calendarService;
    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final SlotAudience slotAudience;
    private final PublicSlotService publicSlotService;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    @GetMapping("/api/slots/{scheduleId}/calendar.ics")
    @ResponseBody
    @Transactional
    @Operation(summary = "Ce créneau, pour votre agenda.",
        description = "Réservé aux personnes du créneau. L'adresse exacte y figure "
            + "lorsqu'elles y ont déjà droit dans l'application — un agenda qui ne dit "
            + "pas où aller ne sert à rien.")
    public ResponseEntity<String> slot(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        if (!slotAudience.participantIds(slot).contains(principal.getId())) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        return ics("creneau.ics", List.of(entryFor(slot, principal.getId())));
    }

    @GetMapping("/api/slots/mine/calendar.ics")
    @ResponseBody
    @Transactional
    @Operation(summary = "Tous mes créneaux à venir, en un fichier.",
        description = "Ce que j'organise et ce que j'ai rejoint, du plus proche au plus "
            + "lointain. Les créneaux passés en sont exclus : un agenda se remplit vers "
            + "l'avant.")
    public ResponseEntity<String> mine(@AuthenticationPrincipal UserPrincipal principal) {
        UUID userId = principal.getId();
        Instant now = Instant.now();

        List<Schedule> joined = participationRepository
            .findByUserIdAndStatusIn(userId,
                List.of(ParticipationStatus.INTERESTED, ParticipationStatus.CONFIRMED))
            .stream()
            .map(SlotParticipation::getSchedule)
            .toList();

        List<SlotCalendarService.Entry> entries =
            Stream.concat(scheduleRepository.findHostedOpenSlots(userId).stream(), joined.stream())
                .filter(s -> s != null && s.getStartsAt().isAfter(now))
                .distinct()
                .sorted(Comparator.comparing(Schedule::getStartsAt))
                .map(s -> entryFor(s, userId))
                .toList();

        return ics("mes-creneaux.ics", entries);
    }

    /**
     * La version publique, pour qui a reçu le lien.
     *
     * <p>Mêmes conditions de visibilité que la page, et surtout : l'adresse ne
     * peut venir que de {@code broadcastableAddress}. Un fichier .ics ne se
     * reprend pas — il est importé, resynchronisé, parfois partagé entre
     * appareils — et y écrire une adresse obtenue pour un demandeur particulier
     * la ferait voyager bien au-delà de lui.
     *
     * <p>Deux adresses pour la même ressource, et la seconde n'est pas
     * cosmétique : le lien « Ajouter à mon agenda » de la page publique est
     * relatif à l'adresse courte que le lecteur a sous les yeux. Lui faire
     * traverser /public/slots/ l'aurait envoyé sur un chemin que rien d'autre
     * ne présente, et que les liens universels n'interceptent pas de la même
     * façon.
     */
    @GetMapping({"/public/slots/{token}/calendar.ics", "/s/{token}/calendar.ics"})
    @ResponseBody
    @Transactional
    public ResponseEntity<String> publicSlot(@PathVariable String token) {
        Schedule slot = publicSlotService.resolve(token, Instant.now());

        return ics("creneau.ics", List.of(new SlotCalendarService.Entry(
            slot,
            SlotAddressVisibility.broadcastableAddress(slot),
            publicBaseUrl + "/s/" + token)));
    }

    /** Le créneau vu par quelqu'un de nommé : adresse résolue pour lui. */
    private SlotCalendarService.Entry entryFor(Schedule slot, UUID userId) {
        String address = SlotAddressVisibility
            .resolve(slot, userId, participationRepository)
            .displayAddress();

        // Le lien vers la page publique n'est ajouté que si le créneau a déjà
        // une adresse publique. En fabriquer une ici rendrait partageable, à
        // l'insu de l'organisateur, un créneau que personne n'avait partagé.
        String publicUrl = slot.getPublicShareToken() != null
            ? publicBaseUrl + "/s/" + slot.getPublicShareToken()
            : null;

        return new SlotCalendarService.Entry(slot, address, publicUrl);
    }

    private ResponseEntity<String> ics(String filename, List<SlotCalendarService.Entry> entries) {
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/calendar;charset=UTF-8"))
            .header("Content-Disposition",
                ContentDisposition.attachment().filename(filename).build().toString())
            .body(calendarService.toIcs(entries));
    }
}
