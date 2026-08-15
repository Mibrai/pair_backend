package org.program.pair.domain.recap;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Les cartes-souvenirs vues depuis le contexte où elles ont de la valeur : un
 * programme, une activité, un profil.
 *
 * <p>Ces trois pages filtraient jusqu'ici le fil géolocalisé côté client, ce
 * qui les vidait : hors du rayon de cinquante kilomètres, ou sans position
 * partagée, il n'y avait plus rien à afficher — et {@code SlotRecapDto} ne
 * porte ni identifiant de programme ni identifiant d'activité pour filtrer
 * juste. Aucune de ces trois lectures ne demande de position : on regarde CE
 * programme, CETTE activité, CE profil, pas ce qui est autour de soi.
 *
 * <p>Toutes rendent un tableau nu de {@code SlotRecapDto} — le DTO existant,
 * sans champ nouveau — trié de la séance la plus récente à la plus ancienne.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecapContextController {

    private final SlotRecapService recapService;

    @GetMapping("/programs/{programId}/recaps")
    @Operation(summary = "Les cartes-souvenirs d'un programme",
        description = "Visibilité graduée, identique à celle déjà en vigueur : un visiteur "
            + "reçoit les cartes publiques du programme, un participant y ajoute les séances "
            + "où sa présence est confirmée quelle que soit leur visibilité, et l'auteur du "
            + "programme reçoit toutes les siennes. Rend un tableau vide — jamais 404 — pour "
            + "un programme sans carte lisible.")
    public List<SlotRecapDto> forProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) {
        return recapService.getForProgram(programId, principal.getId());
    }

    @GetMapping("/activities/{activityId}/recaps")
    @Operation(summary = "Les cartes-souvenirs publiques d'une activité",
        description = "Publiques uniquement, tous organisateurs confondus. activityId est la "
            + "clé du référentiel — celle que porte déjà BrowsedActivityDto.activityId — et "
            + "non le libellé : deux « Yoga du soir » de deux auteurs différents ne se "
            + "mélangent pas.")
    public List<SlotRecapDto> forActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID activityId) {
        return recapService.getForActivity(activityId, principal.getId());
    }

    @GetMapping("/users/{userId}/recaps")
    @Operation(summary = "Les cartes-souvenirs publiques des créneaux animés par quelqu'un",
        description = "Publiques uniquement, y compris pour un participant de la séance : une "
            + "carte privée d'un tiers n'a pas à apparaître sur un profil. Ceux qui y étaient "
            + "la retrouvent par /api/recaps/mine.")
    public List<SlotRecapDto> forUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        return recapService.getForHost(userId, principal.getId());
    }
}
