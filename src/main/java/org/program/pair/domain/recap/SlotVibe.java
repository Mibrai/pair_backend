package org.program.pair.domain.recap;

import java.util.Optional;

/**
 * Vocabulaire d'ambiance d'un créneau — liste strictement fermée.
 *
 * <p>Trois raisons, toutes structurantes : l'agrégation multilingue devient
 * triviale (une clé de traduction par valeur, côté client), il n'y a aucun
 * contenu inapproprié à modérer, et contribuer reste <b>un tap</b> plutôt
 * qu'une rédaction.
 *
 * <p>Le serveur ne renvoie donc <b>aucun libellé</b> : il renvoie la valeur,
 * le client l'affiche dans la langue de son utilisateur.
 *
 * <p>Aucune de ces valeurs ne décrit une performance ni ne classe qui que ce
 * soit : elles décrivent le moment, jamais les personnes.
 */
public enum SlotVibe {
    RELAXED,
    ENERGETIC,
    FRIENDLY,
    TECHNICAL,
    BEGINNER_FRIENDLY,
    GOOD_LAUGH,
    FOCUSED,
    OUTDOORS;

    /**
     * Lecture tolérante d'une valeur reçue : {@link Optional#empty()} plutôt
     * qu'une exception.
     *
     * <p>C'est ce qui permet au service de rendre un {@code 422
     * RECAP_INVALID_VIBES} nommé. Typer directement l'enum dans le corps de
     * requête ferait échouer la désérialisation avant d'atteindre le service,
     * et le client recevrait un {@code 400 INVALID_JSON} générique qu'il ne
     * saurait pas traduire.
     */
    public static Optional<SlotVibe> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
