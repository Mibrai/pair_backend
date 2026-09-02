package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Refermer une veille avec son code.
 *
 * <p><b>{@code notifyGuardian} est une exception que la personne veillée s'accorde
 * à elle-même.</b> Le module s'interdit qu'une notification apprenne à un tiers
 * qu'une veille s'est terminée. Ce drapeau ne lève pas cette règle : il n'existe
 * aucun type de notification pour ce message, il est faux par défaut, il est porté
 * par la personne veillée sur cet envoi-là et pour son seul contact déjà désigné et
 * consentant, et le message n'évoque pas la veille (voir
 * {@code AlertMessages.retourAnnonceSms}). Ce que le système ne dira jamais de
 * lui-même, quelqu'un reste libre de le dire.
 *
 * <p><b>{@code enteredAt} fait foi, pas l'heure de réception.</b> Une saisie faite
 * hors ligne à 23:32 et transmise à 23:58 doit lever la veille comme si elle
 * arrivait à 23:32 — sinon rentrer chez soi dans un immeuble mal couvert
 * déclencherait une alerte chez un proche. C'est l'heure saisie qui date la
 * clôture dans la chronologie.
 */
@Schema(description = "Clôture d'une veille par le code de retour.")
public record CloseRequest(

    @Schema(description = "Le code de retour, tel que l'utilisateur le saisit.")
    @NotBlank(message = "Le code est obligatoire.")
    String code,

    @Schema(description = "L'heure à laquelle l'utilisateur a saisi le code. Fait foi, "
        + "même transmise plus tard.")
    @NotNull(message = "L'heure de saisie est obligatoire.")
    Instant enteredAt,

    @Schema(description = "Prévenir mon contact que je suis bien rentrée. Facultatif, faux "
        + "par défaut : absent ou faux, personne n'est prévenu, exactement comme aujourd'hui. "
        + "Sans effet quand une alerte était partie — le contact reçoit alors la levée, qui "
        + "n'est pas facultative et dit déjà que tout va bien.",
        defaultValue = "false")
    Boolean notifyGuardian
) {

    /** Faux par défaut : l'absence du champ ne prévient personne. */
    public boolean veutPrevenirLeContact() {
        return Boolean.TRUE.equals(notifyGuardian);
    }
}
