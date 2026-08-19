package org.program.pair.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Ce qui part sur {@code /user/queue/typing} quand quelqu'un se met à écrire.
 *
 * <p>Volontairement pauvre : un identifiant de fil, un identifiant de personne,
 * un booléen. Y joindre le nom d'affichage aurait épargné une jointure au client,
 * mais il tient déjà la liste des membres du fil qu'il a ouvert — et ce message
 * part à chaque frappe, donc à un rythme sans commune mesure avec celui des
 * messages.
 */
@Schema(description = "Indicateur de saisie. Éphémère : rien n'en est conservé, "
    + "et le serveur n'émet aucun rappel. Le client doit l'effacer de lui-même "
    + "après quelques secondes sans nouvelle — un émetteur qui perd sa connexion "
    + "ne pourra jamais annoncer qu'il s'est arrêté.")
public record TypingEventDto(
    UUID conversationId,
    UUID userId,
    boolean typing
) {}
