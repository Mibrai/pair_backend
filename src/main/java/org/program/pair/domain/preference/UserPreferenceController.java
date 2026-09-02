package org.program.pair.domain.preference;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.preference.dto.PreferenceValue;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Les réglages privés de l'appelant : une clé, une valeur opaque.
 *
 * <p><b>Le propriétaire, et lui seul.</b> Il n'existe aucune route pour lire la
 * préférence de quelqu'un d'autre, aucune pour chercher par valeur, et ces
 * données ne sont jointes à aucun DTO public. Ce n'est pas une restriction posée
 * par-dessus : c'est la forme entière de la fonctionnalité, et elle a été choisie
 * plutôt qu'une donnée structurée pour cette raison.
 *
 * <p><b>Ce qu'on a refusé de construire à la place.</b> Le besoin d'origine était
 * une liste de « proches » qu'un client ordonnait en tête de son écran, et qu'il
 * rangeait sur l'appareil faute de mieux. La forme évidente aurait été une
 * relation d'amitié entre deux comptes. Elle est refusée, à la demande explicite
 * du client et pour une raison qui vaut d'être répétée à l'endroit du code : une
 * relation stockée devient interrogeable et exportable, et un écran finit par
 * afficher « X vous a retiré de ses amis ». Il suffirait d'une ligne, écrite un
 * jour par quelqu'un qui n'aura pas lu cette page. <b>Ne pas avoir la donnée est
 * la seule garantie qui tienne dans le temps.</b>
 *
 * <p>Une valeur opaque appartenant à une seule personne ne peut pas devenir, par
 * inadvertance, une information sur quelqu'un d'autre.
 */
@RestController
@RequestMapping("/api/users/me/preferences")
@RequiredArgsConstructor
@Tag(name = "Preferences", description = "Réglages privés de l'appelant")
@SecurityRequirement(name = "bearerAuth")
public class UserPreferenceController {

    private final UserPreferenceService preferenceService;

    @GetMapping("/{key}")
    @Operation(summary = "Lire un réglage privé",
        description = "404 si la clé n'a jamais été posée. La valeur est rendue telle qu'elle "
            + "a été écrite ; le serveur ne l'interprète pas.")
    public PreferenceValue get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String key) {
        return new PreferenceValue(preferenceService.read(principal.getId(), key));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Poser un réglage privé",
        description = "Crée ou remplace. La clé est un identifiant technique du client "
            + "(`[a-zA-Z0-9._-]`, 64 au plus) ; la valeur est libre, 8192 caractères au plus.")
    public PreferenceValue put(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String key,
            @Valid @RequestBody PreferenceValue body) {
        return new PreferenceValue(preferenceService.write(principal.getId(), key, body.value()));
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Effacer un réglage privé",
        description = "Idempotent : effacer une clé absente réussit.")
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String key) {
        preferenceService.erase(principal.getId(), key);
    }
}
